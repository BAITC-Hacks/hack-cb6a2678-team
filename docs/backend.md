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
│  └─ AgentTools.kt         — инструменты агента (@Tool) + компактный вид прогноза для LLM
├─ config/WebConfig.kt      — CORS для /api/**, бин Clock
├─ dto/Dto.kt               — все DTO публичного API
└─ mock/MockDataService.kt  — детерминированные моки (будут заменены реальными сервисами)

backend/src/main/resources/
├─ application.yaml         — настройки Ollama, ретраи, таймауты, Swagger
└─ static/openapi.yaml      — контракт API (источник правды, его показывает Swagger UI)
```

## Запуск

```bash
ollama serve & ollama pull qwen3:8b     # или docker compose up -d из корня
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

## Как менять API

1. Правка `static/openapi.yaml` (новые поля — `nullable`, не ломать фронт).
2. DTO в `dto/Dto.kt`, логика, контроллер.
3. Тест в `WesControllerTests.kt` / `AgentTests.kt`.
4. Если меняется то, что видит агент, — описания `@Tool` и системный промпт.

## Что дальше (см. `roadmap.md`)

1. Клиент Open-Meteo с защитой от утечки (`T` → только прогоны, опубликованные ≤ `T`) и файловым кэшем.
2. HTTP-клиент к ML-сервису по `contracts/ml-service.openapi.yaml`.
3. Реальный цикл агента на `POST /forecast/run` с записью шагов в `agent-log`.
4. Backtest-раннер и хранилище; замена `MockDataService` реальными сервисами без изменения API.

## Грабли

- Jackson 3: импорты `tools.jackson.*`. В тестах `JsonNode` — через индекс (`node[i]`), `doubleValue()`.
- Swagger показывает статический `openapi.yaml`: новый эндпоинт без записи там не виден.
- Ollama в Docker на macOS — только CPU (медленно). Для разработки — нативный `brew install ollama`.
