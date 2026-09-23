# AGENTS.md — контекст проекта для AI-ассистентов (Claude Code, Codex и др.)

Этот файл читают AI-ассистенты перед работой с репозиторием. Людям он тоже полезен как краткая шпаргалка.
Для жюри — [README.md](README.md). Подробности — `docs/` (индекс: `docs/README.md`).

## Что за проект

Хакатон HackAlem AI, кейс «Agentic AI для прогнозирования выработки ВЭС».
Система сама строит почасовой прогноз выработки ветроэлектростанции (2 турбины) на 24–48 ч: выпуск в день D
в 12:00 местного → сутки D+1 и D+2. Цикл агента: погода у турбины → прогноз ML → проверка → сравнение с прошлым
выпуском → отчёт LLM с самопроверкой → сохранение.

Backtest — выпуски 31.01–26.02.2026 (покрывают весь февраль), результаты в `data/cycles/`.
Факта за февраль в данных нет, поэтому точность меряется на отложенном январе 2026. ТЗ и критерии — `docs/task.md`.

## Команда и зоны ответственности

| Роль | Зона | Документ |
|---|---|---|
| Бэкенд (Spring Boot + Kotlin + Spring AI) | `backend/` — API, агент, погода, метрики, хранение циклов | `docs/backend.md` |
| ML (Python) | `ML/` — модель, FastAPI `/v1/forecast`, `/v1/evaluation` | `ML/README.md`, `ML/TRAINING.md` |
| Фронтенд (React) | `frontend/` — дашборд, журнал агента, метрики, чат | `docs/frontend.md` |

## Структура репозитория

```
AGENTS.md / CLAUDE.md       — этот контекст
README.md                   — для жюри: что сделано, запуск, проверка, ограничения
docker-compose.yml          — все сервисы: ml, backend, frontend, ollama
contracts/ml-service.openapi.yaml       — контракт бэкенд ↔ ML (v2.0)
backend/src/main/resources/static/openapi.yaml — контракт фронт ↔ бэкенд (v1.2), источник правды для Swagger
backend/                    — Spring Boot 4.1, Kotlin 2.3, Spring AI 2.0.1, Java toolchain 17
ML/                         — Python ML-сервис (FastAPI :8000), модели, датасет, кэш погоды Previous Runs
frontend/                   — React 19 + Vite; в Docker — nginx с прокси /api
data/cycles/                — результаты backtest: циклы агента <турбина>/<дата выпуска>.json (коммитятся)
data/weather-cache/         — кэш Open-Meteo Single Runs для агента (коммитится — офлайн-воспроизводимость)
scripts/backtest.py         — прогон цикла агента по всем датам × турбинам
docs/                       — документация; docs/archive/ — завершённые планы и задания
```

## Текущее состояние (обновлять при изменениях!)

Всё работает на реальных данных, моков нет.

- **Прогноз:** `/api/forecast` — ML (`backend/.../forecast/`, клиент `backend/.../ml/`), поля v2 контракта ML
  (время UTC, погода по часам, граница выпуска погоды) используются напрямую. `/api/forecast/revisions` — версии
  прогноза одних суток из выпусков X−2 и X−1.
- **Цикл агента:** `POST /api/forecast/run` + `GET /api/agent-log` (`backend/.../cycle/`). Отчёт LLM проходит
  самопроверку (`unsupportedValues`): времена и проценты из текста должны быть в данных, иначе — шаблон.
- **Backtest:** 54 цикла (27 дат × 2 турбины), все успешны, все отчёты LLM прошли самопроверку.
- **Метрики:** `/api/metrics` — отложенный тест ML за январь (`GET /v1/evaluation`) + SCADA для бейзлайна persistence.
- **Чат:** `POST /api/agent/chat`, инструменты `getForecast`, `getForecastRevisions`, `getWeather`, `getMetrics`, `listTurbines`.
  Всё, что видит агент, — готовые выводы по местному времени, без сырых UTC-меток.
- **Погода для агента:** `backend/.../weather/`, Open-Meteo Single Runs (ECMWF IFS HRES), прогон ≤ момента прогноза.
- **Запуск:** `docker compose up --build` проверен (ml + backend + frontend, Ollama нативная через `OLLAMA_BASE_URL`).
- План и статусы — `docs/roadmap.md`.

## Команды

```bash
# Всё в Docker
docker compose up --build                  # UI :3000, API :8080, ML :8000, Ollama :11434
docker compose up --build ml backend frontend   # без LLM

# Без Docker
ollama serve & ollama pull qwen3:8b        # LLM (на macOS нативно быстрее — GPU)
cd ML && pip install -r requirements.txt && uvicorn windml.api:app --port 8000   # macOS: brew install libomp
cd backend && ./gradlew bootRun            # http://localhost:8080, Swagger: /swagger-ui.html
cd frontend && npm ci && npm run dev       # http://localhost:5173

python3 scripts/backtest.py [from] [to]    # цикл агента для дат × турбин → data/cycles/ (~10 мин с LLM)
cd backend && ./gradlew test               # тесты без сети, ML и LLM
cd ML && python -m pytest tests
```

## Правила, которые нельзя нарушать

1. **Никакой утечки данных из будущего.** Для прогноза в момент `T` используются только данные, опубликованные до `T`:
   архивные *прогнозы* погоды (не фактическая погода / reanalysis), выпущенные ≤ `T` с учётом задержки публикации.
   Каждый прогноз хранит `weatherIssuedAt ≤ forecastIssuedAt`; бэкенд отказывается отдавать прогноз, если это не так.
   Модель обучается только на данных ≤ 2026-01-31; настройки подбираются на валидации до января.
2. **Все времена в API и коде — UTC** (`Instant`, ISO-8601 с `Z`). Местное время (Asia/Almaty, UTC+5) — только для
   отображения на фронте и во фразах для LLM.
3. **Contract-first.** Меняешь API → сначала `openapi.yaml` (или `contracts/ml-service.openapi.yaml`), потом код и тесты.
   Новые поля добавлять как необязательные (nullable), чтобы не ломать других участников.
4. **Мощность нормализована 0..1** (1 = номинал турбины), не МВт.
5. **Секреты не коммитить.** Ключи NVIDIA/OpenAI, выданные на хакатоне, — только в локальном `.env` (в `.gitignore`).
   Всё остальное (модели, кэши, датасет, результаты backtest) коммитится — жюри запускает проект без ключей и сети.
6. **Воспроизводимость важнее красоты**: 25 из 100 баллов — README и воспроизводимость.
7. После новой модели ML — перезапустить `scripts/backtest.py` и закоммитить `data/cycles/`.

## Стиль кода

- Kotlin: как в существующем коде — DTO в `dto/Dto.kt`, комментарии на русском, короткие и только про «почему».
- Названия enum-значений в API — `snake_case` (`storm_cutout`), поля JSON — `camelCase`.
- Тесты — MockMvc + `kotlin.test`, по образцу `WesControllerTests.kt`. Тесты не должны требовать Ollama или сеть:
  ML подменяется записанными ответами (`support/FixtureMl.kt`), погода — офлайн.
- Коммиты: префикс `ADD:` / `FIX:` / `UPD:` + кратко на английском.

## Известные грабли

- Ollama по умолчанию даёт ~2k токенов контекста и **молча обрезает промпт**. Выставлено `num-ctx: 16384`.
- qwen3:8b изредка **зацикливается** и генерирует тысячи токенов; Ollama не прерывает генерацию при отключении клиента.
  Выставлено `num-predict: 1024`.
- Spring AI по умолчанию делает 10 ретраев с растущей паузой → без Ollama запрос висит минутами. Ограничено `retry.max-attempts: 2`.
- Малая LLM плохо считает и путается во времени и днях. Инструменты агента: принимают то, о чём спрашивают
  (календарные дни), отдают готовые выводы текстом (`conclusions`) в местном времени, без сырых UTC-меток
  (модель подписывала их как местные), почасовые данные — только по запросу. Проверять на живой модели по 2+ раза.
- Пример с конкретными числами в промпте LLM копирует в ответ — в примерах только плейсхолдеры.
- Jackson `SnakeCaseStrategy`: `windSpeed100m` → `wind_speed100m`; для полей с цифрами — явный `@JsonProperty`.
- Spring Boot 4 / Jackson 3: пакет `tools.jackson.*`, а не `com.fasterxml.jackson.*` (аннотации — по-прежнему `com.fasterxml`).
- Swagger UI показывает статический `openapi.yaml`, а не сгенерированный из кода — новый эндпоинт дописывать туда руками.

## Открытые вопросы

См. «Открытые вопросы» в `docs/roadmap.md`. Если задача упирается в один из них — спроси человека, не угадывай.
