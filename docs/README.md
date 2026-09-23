# Документация

| Документ | Для кого | О чём |
|---|---|---|
| [overview.md](overview.md) | все | Что за продукт простыми словами: зачем, экраны, демо |
| [task.md](task.md) | все | ТЗ хакатона и критерии оценки |
| [architecture.md](architecture.md) | все | Компоненты, потоки данных, агент, защита от утечки, принятые решения |
| [roadmap.md](roadmap.md) | все | План по этапам, статус, открытые вопросы |
| [ml-integration.md](ml-integration.md) | бэкенд, ML | План стыковки ML-сервиса с бэкендом: решения, изменения API v1.2, задачи |
| [backend.md](backend.md) | бэкенд | Устройство `backend/`, как добавить инструмент агента, конфиг, грабли |
| [ml.md](ml.md) | ML | Что сделать ML-сервису, данные, контракт, требования |
| [frontend.md](frontend.md) | фронтенд | Экраны → эндпоинты, смысл полей, поллинг |

Контракты:

- Публичный API (фронт ↔ бэкенд): [`backend/src/main/resources/static/openapi.yaml`](../backend/src/main/resources/static/openapi.yaml), Swagger UI — http://localhost:8080/swagger-ui.html
- ML-сервис (бэкенд ↔ ML): [`contracts/ml-service.openapi.yaml`](../contracts/ml-service.openapi.yaml)

Работаете через Claude Code или Codex — они сами прочитают [`AGENTS.md`](../AGENTS.md) в корне. Хорошие первые вопросы ассистенту:

- «Прочитай docs/ и объясни, что мне делать в моей зоне (ML / фронт / бэкенд)»
- «Какие эндпоинты нужны для экрана "Прогноз" и что значит каждое поле?»
- «Сверь мою реализацию /predict с contracts/ml-service.openapi.yaml»
