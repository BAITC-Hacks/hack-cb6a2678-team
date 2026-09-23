# wind-agent — агент на Spring AI

Агент (LLM + tool calling) управляет ML-инструментом `windml` через REST.
LLM **не обучается**: она выбирает, какие инструменты вызвать, и интерпретирует ответы.
Обучается только ML-модель в Python (`python -m windml train`).

```
Spring Boot + Spring AI (агент, LLM)  ──HTTP──►  windml.api (FastAPI)  ──►  LightGBM + Open-Meteo
```

## Запуск

Типизированный прямой доступ к ML без вызова LLM:
`POST /ml/forecast`, `GET /ml/models`, `GET /ml/models/{site}`.
Параметры запроса: `site` (`turbine_1`/`turbine_2`), `issue_date` (`YYYY-MM-DD`),
`weather_source` (`previous_runs`/`live`). Бэкенд обращается к FastAPI по
`WINDML_URL` и передаёт его JSON-ответ и HTTP-ошибки клиенту.
Примеры и результаты обучения: [../TRAINING.md](../TRAINING.md).

```bash
# 1. Python-часть (в корне проекта)
pip install -r requirements.txt
python -m windml train
uvicorn windml.api:app --port 8000

# 2. Агент
cd spring-agent
export ANTHROPIC_API_KEY=...
./mvnw spring-boot:run        # или: mvn spring-boot:run

# 3. Использование
curl "localhost:8080/agent/forecast?date=2026-01-31"
curl "localhost:8080/agent/replay?start=2026-01-31&end=2026-02-27"
```

Версии: Spring Boot 3.5, Spring AI 1.1.x (стабильная ветка), Java 21.
Сменить LLM-провайдера — заменить стартер в pom.xml и блок `spring.ai.*` в application.yml.
