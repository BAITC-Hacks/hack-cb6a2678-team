# hack-cb6a2678-team
Hackathon team repository for Во имя Омниссии!

Agentic AI-система почасового прогноза выработки ВЭС на 24–48 ч (кейс HackAlem AI).
Документация — [`docs/`](docs/README.md), контекст для Claude Code / Codex — [`AGENTS.md`](AGENTS.md).

## Backend (моки API)

Бэкенд сейчас отдаёт детерминированные моки по контракту `backend/src/main/resources/static/openapi.yaml`.

```bash
cd backend && ./gradlew bootRun   # http://localhost:8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Контракт ML-сервиса (FastAPI, `/predict`, `/health`): `contracts/ml-service.openapi.yaml`
- `GET /api/meta` — границы backtest (прогнозы на 2026-01-31…2026-02-27), горизонты, версии модели и LLM
- Ревизии: `revision=1` — плановый прогноз в 00:00 UTC, `2..4` — пересчёты в 06/12/18 UTC; `GET /api/forecast/revisions`
- Каждый прогноз указывает `weatherIssuedAt` (≤ `forecastIssuedAt`) — защита от утечки данных из будущего
- Все времена в API — UTC
- Турбины: `t1`, `t2`; неизвестная турбина → 404
- Факт (`actualPower`) есть до 2026-03-01, дальше — `null`
- `/api/agent-log`: каждый день с `dayOfMonth % 7 == 3` первым шагом идёт `retrying`
- `POST /api/forecast/run` → потом поллинг `/api/agent-log?date=…&turbineId=…`: шаги завершаются по одному каждые 2 с (`running` → `success`)
- CORS открыт для `/api/**`

## LLM-агент (Spring AI + Ollama)

Агент — `POST /api/agent/chat` (есть в Swagger, тег **Agent**). LLM сама решает, какие инструменты вызвать
(`listTurbines`, `getForecast`, `getMetrics`), в ответе — текст и журнал вызовов `toolCalls`.

```bash
docker compose up -d                  # Ollama + одноразовая загрузка модели (~5 ГБ, первый раз несколько минут)
docker compose logs -f ollama-pull    # дождаться окончания загрузки
cd backend && ./gradlew bootRun
```

```bash
curl -X POST localhost:8080/api/agent/chat -H 'Content-Type: application/json' \
  -d '{"message":"Какая ожидается выработка турбины t1 на 5 февраля 2026?"}'
```

Настройки через переменные окружения:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `OLLAMA_MODEL` | `qwen3:8b` | Модель (нужна поддержка tool calling; для слабых машин — `qwen3:4b`) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Адрес Ollama |
| `OLLAMA_PULL_STRATEGY` | `never` | `when_missing` — бэкенд сам скачает модель при старте (без docker compose) |

На macOS Ollama в Docker работает только на CPU. Для быстрой разработки лучше нативный Ollama
(`brew install ollama && ollama serve`, затем `ollama pull qwen3:8b`) — он использует GPU Apple Silicon.
Если Ollama не запущена, агент отвечает `503`.
