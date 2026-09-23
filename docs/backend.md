# Бэкенд

Spring Boot 4.1 · Kotlin 2.3 · Java toolchain 17 · Spring AI 2.0.1 (Ollama) · springdoc 3 · Jackson 3.

## Структура

```
backend/src/main/kotlin/com/ybkuanysh/backend/
├─ BackendApplication.kt
├─ api/
│  ├─ WesController.kt      — /api/meta, /turbines, /forecast, /forecast/revisions, /forecast/run, /metrics, /agent-log
│  └─ ErrorHandler.kt       — 400 / 404 / 503 (LLM недоступна)
├─ agent/
│  ├─ AgentController.kt    — POST /api/agent/chat
│  ├─ AgentService.kt       — ChatClient, системный промпт, журнал вызовов
│  ├─ AgentTools.kt         — инструменты агента (@Tool) + компактный вид прогноза для LLM
│  └─ AgentWeatherView.kt   — компактный вид погоды для LLM: итоги и готовые выводы по дням
├─ config/WebConfig.kt      — CORS для /api/**, бин Clock
├─ ml/                     — клиент ML-сервиса по contracts/ml-service.openapi.yaml (модели ответа, RestClient)
├─ cycle/                  — цикл агента: ForecastCycleService (шаги, повторы, проверки), CycleStore (память + JSON),
│                             ReportWriter (отчёт LLM + самопроверка unsupportedValues)
├─ turbine/TurbineRegistry.kt — турбины и координаты
├─ metrics/                — точность на отложенном тесте: EvaluationRepository (ML /v1/evaluation или CSV),
│                             ScadaRepository (факт для бейзлайна persistence), MetricsService
├─ forecast/
│  ├─ ForecastService.kt    — ответ ML → публичный ForecastResponse; версии прогноза на сутки
│  └─ ForecastAnalytics.kt  — сводка, алерты по правилам, шаблонный отчёт (общие с моками)
├─ weather/
│  ├─ WeatherService.kt     — выбор прогона, опубликованного ≤ T; фолбэк на старые прогоны; файловый кэш
│  ├─ OpenMeteoClient.kt    — HTTP к Single Runs API, разбор ответа
│  ├─ WeatherController.kt  — GET /api/weather (служебный, для отладки и агента)
│  ├─ WeatherModels.kt      — WeatherHour (как в ML-контракте), WeatherRun, WeatherForecast, исключения
│  └─ WeatherProperties.kt  — настройки weather.*
├─ dto/Dto.kt               — все DTO публичного API

backend/src/main/resources/
├─ application.yaml         — настройки Ollama, ретраи, таймауты, Swagger
└─ static/openapi.yaml      — контракт API (источник правды, его показывает Swagger UI)
```

## Запуск

```bash
ollama serve & ollama pull qwen3:8b     # или docker compose up -d из корня
cd ML && uvicorn windml.api:app --port 8000   # ML-сервис, без него /api/forecast отвечает 503
cd backend && ./gradlew bootRun
open http://localhost:8080/swagger-ui.html
./gradlew test                          # Ollama и сеть не нужны
```

## Конфигурация (переменные окружения)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | адрес Ollama |
| `OLLAMA_MODEL` | `qwen3:8b` | модель; нужна поддержка tool calling |
| `OLLAMA_PULL_STRATEGY` | `never` | `when_missing` — бэкенд сам скачает модель при старте |
| `ML_BASE_URL` | `http://localhost:8000` | адрес ML-сервиса |
| `ML_LOCAL_TZ` | `Asia/Almaty` | местный пояс станции (пока ML не отдаёт `local_tz` сам) |
| `ML_EVALUATION_DIR` | `../ML/outputs/training` | CSV отложенного теста, пока ML не отдаёт `/v1/evaluation` |
| `ML_SCADA_DIR` | `../ML/data/raw` | SCADA из датасета — для бейзлайна persistence |
| `CYCLE_STORE_DIR` | `../data/cycles` | куда сохранять циклы агента (JSON) |
| `CYCLE_LLM_REPORT` | `true` | `false` — отчёт по шаблону, без Ollama |
| `WEATHER_MODEL` | `ecmwf_ifs` | модель Open-Meteo |
| `WEATHER_PUBLICATION_DELAY` | `7h` | через сколько после инициализации прогон считается опубликованным |
| `WEATHER_CACHE_DIR` | `../data/weather-cache` | кэш ответов (путь от `backend/`) |
| `WEATHER_OFFLINE` | `false` | `true` — только кэш, без сети |

Важные настройки в `application.yaml` и почему они такие:

- `spring.ai.ollama.chat.num-ctx: 16384` — по умолчанию ~2k токенов, Ollama молча обрезает промпт.
- `spring.ai.ollama.chat.think: false` — «размышления» qwen3 замедляют ответ в разы.
- `spring.ai.retry.max-attempts: 2` — по умолчанию 10 ретраев, без Ollama запрос висит минутами.
- `spring.http.clients.read-timeout: 5m` — локальная LLM на CPU отвечает долго.

## Как устроен агент

`AgentService` создаёт `ChatClient` с системным промптом и инструментами из `AgentTools`.
На каждый запрос в `toolContext` кладётся список-журнал; каждый инструмент через `traced(...)` пишет туда
имя, аргументы и успех. Журнал возвращается в ответе как `toolCalls`.

Если инструмент бросает исключение, Spring AI передаёт текст ошибки модели — она может исправить аргументы и повторить.

### Как добавить инструмент

```kotlin
@Tool(description = "Что делает инструмент — модель выбирает инструмент по этому описанию, пиши подробно")
fun myTool(
    @ToolParam(description = "Смысл и формат параметра") param: String,
    @ToolParam(description = "...", required = false) optional: Int?,
    ctx: ToolContext,                              // всегда последним — для журнала
): MyResult = traced(ctx, "myTool", mapOf("param" to param)) {
    service.doSomething(param)
}
```

Правила для инструментов:

- Возвращать **компактные** данные и **готовые итоги** — LLM плохо считает и ограничена контекстом.
- Момент прогноза `T` (когда появится реальный цикл) подставлять из кода, не принимать от LLM.
- Даты от LLM — строки `yyyy-MM-dd`, парсить в инструменте.
- Параметры — в терминах вопроса пользователя (календарные дни), а не внутренних понятий (момент + горизонт):
  иначе модель отвечает про «лишние» часы горизонта.
- Выводы о рисках формирует код (`conclusions`), модель их только пересказывает. Почасовые данные — по флагу `detailed`.
- После изменения инструмента задайте агенту 2–3 типичных вопроса по 2 раза и сверьте ответы с REST API.

Инструменты сейчас: `listTurbines`, `getForecast` и `getForecastRevisions` (ML), `getWeather` (Open-Meteo),
`getMetrics` (отложенный тест, готовые выводы).
Все дни и часы для агента — по местному времени станции (`ml.local-tz`).

Тесты не ходят в сеть: ML подменяется сохранёнными ответами (`support/FixtureMl.kt`, `src/test/resources/ml/`),
погода — офлайн (`src/test/resources/config/application.yaml`).

## Погода

`WeatherService.forecastAt(lat, lon, T, horizonHours)`:

1. `latestAvailableRun(T)` — последний прогон с `init + publicationDelay ≤ T`.
2. Берёт его из кэша или Open-Meteo, оставляет часы `(T, T + horizon]`.
3. Если прогон недоступен или в нём нет полного горизонта — пробует на 6 ч старше (до `maxFallbackRuns`).
4. Любая попытка взять прогон, опубликованный после `T`, — `LeakageException` (баг, не ретраится).

Проверить: `GET /api/weather?turbineId=t1&at=2026-02-01T00:00:00Z`. Тесты — `WeatherServiceTests` (без сети,
включая проверку «ни одного часа и прогона из будущего» по всему февралю).

## Как менять API

1. Правка `static/openapi.yaml` (новые поля — `nullable`, не ломать фронт).
2. DTO в `dto/Dto.kt`, логика, контроллер.
3. Тест в `WesControllerTests.kt` / `AgentTests.kt`.
4. Если меняется то, что видит агент, — описания `@Tool` и системный промпт.

## Что дальше (см. `roadmap.md`)

1. Dockerfile бэкенда и все сервисы в `docker-compose.yml`.

Backtest: `python3 scripts/backtest.py [from] [to]` — цикл агента для каждой даты выпуска и турбины через API,
результаты в `data/cycles/` (коммитятся). Перезапускать после каждой новой модели ML.

## Грабли

- Jackson 3: импорты `tools.jackson.*`. В тестах `JsonNode` — через индекс (`node[i]`), `doubleValue()`.
- Swagger показывает статический `openapi.yaml`: новый эндпоинт без записи там не виден.
- qwen3:8b изредка зацикливается и генерирует тысячи токенов; Ollama при этом не прерывает генерацию, даже когда
  клиент отключился. Поэтому `spring.ai.ollama.chat.num-predict: 1024` — потолок длины любого ответа.
- Jackson `SnakeCaseStrategy` превращает `windSpeed100m` в `wind_speed100m` (без `_` перед цифрами) — для полей
  с цифрами нужен явный `@JsonProperty`. Тест разбора ответа ML — на настоящем JSON (`MlModelsTests`).
- `@ConfigurationProperties` с полем `Path` и значением `../...` не биндится (Spring считает это ресурсом веб-приложения) — храните путь строкой.
- Ollama в Docker на macOS — только CPU (медленно). Для разработки — нативный `brew install ollama`.
