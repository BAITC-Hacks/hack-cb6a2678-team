# hack-cb6a2678-team
Hackathon team repository for Во имя Омниссии!

## Backend (моки API)

Бэкенд сейчас отдаёт детерминированные моки по контракту `backend/src/main/resources/static/openapi.yaml`.

```bash
cd backend && ./gradlew bootRun   # http://localhost:8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Турбины: `t1`, `t2`; неизвестная турбина → 404
- Факт (`actualPower`) есть до 2026-03-01, дальше — `null`
- `/api/agent-log`: каждый день с `dayOfMonth % 7 == 3` первым шагом идёт `retrying`
- `POST /api/forecast/run` → потом поллинг `/api/agent-log?date=…&turbineId=…`: шаги завершаются по одному каждые 2 с (`running` → `success`)
- CORS открыт для `/api/**`
