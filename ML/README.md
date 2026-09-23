# windml — прогноз выработки двух турбин

FastAPI-сервис по [контракту v2.0](../contracts/ml-service.openapi.yaml).
Выпуск D в 12:00 `Asia/Almaty` → сутки D+1 и D+2, 48 часов. Мощность — доля номинала 0…1.
Модели, SCADA, погодный кэш и январские тестовые прогнозы уже в репозитории.
Статус A1–A8/B1–B5, метрики и ограничения: [TRAINING.md](TRAINING.md).

## Запуск без Docker

Из корня репозитория (Python 3.12 или 3.14):

```bash
cd ML
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m uvicorn windml.api:app --host 0.0.0.0 --port 8000
```

На macOS для LightGBM требуется `brew install libomp`.
WINDML_ROOT определяется автоматически. Для запрета сетевых запросов задайте
`WINDML_OFFLINE=1` (`$env:WINDML_OFFLINE='1'` в PowerShell).
Swagger: http://localhost:8000/docs. Бэкенд и LLM для работы ML не нужны.

```bash
curl -X POST http://localhost:8000/v1/forecast -H "Content-Type: application/json" \
  -d '{"site":"turbine_1","issue_date":"2026-01-31","weather_source":"previous_runs"}'
```

## Docker

Из корня репозитория:

```bash
docker build -t windml ML
docker run --rm -p 8000:8000 windml
```

Сборка скачивает базовый образ и зависимости; готовый образ содержит все модели и кэш,
во время работы использует WINDML_OFFLINE=1. Проверка с отключённой сетью:

```bash
docker run -d --rm --network none --name windml-offline windml
docker exec -i windml-offline python - < ML/scripts/docker_smoke.py
docker stop windml-offline
```

В PowerShell вместо перенаправления `<`:

```powershell
Get-Content -Raw -Encoding utf8 ML/scripts/docker_smoke.py | docker exec -i windml-offline python -
```

При `--network none` HTTP проверяется внутри контейнера через loopback;
публикация порта не делает такой контейнер доступным с хоста. Healthcheck: GET /health.

## API

| Маршрут | Назначение |
|---|---|
| GET /health | Живость |
| GET /v1/models | Наличие моделей |
| GET /v1/models/{site} | Версия рабочей модели, последний обучающий час, пояса, NWP и параметры |
| POST /v1/forecast | site, issue_date, weather_source → 48 часов и анализ |
| GET /v1/evaluation/{site} | 1488 строк январского теста, версия тестовой модели |
| GET /tools, POST /tools/{name} | Сохранённые инструменты агента |

Старые местные time/target_start/target_end сохранены. Для интеграции используйте
issued_at_utc/target_start_utc/target_end_utc/hourly[].time_utc с Z.
Почасовые данные включают средний ветер, разброс ансамбля и температуру;
предупреждения — UTC-интервалы. Отсутствующий факт — null, простои включены в метрики.
Неверный ввод → 422; нет модели/погоды или допустимость выпуска не доказана → 503.

## Данные и честность

- SCADA: data/raw/turbine_1.csv и turbine_2.csv, приборный пояс Etc/GMT-5 выбран до января.
  Операционный пояс отдельно: Asia/Almaty.
- Вход — архивные прогнозы ECMWF/GFS/ICON. Strict: D+1 использует previous_day2, D+2 — previous_day3.
  Максимум target_utc − N×24h проверяется относительно момента выпуска.
- Тестовые модели обучены по 31.12.2025, рабочие — по 31.01.2026.
  Все решения по параметрам/калибровке приняты до январского теста.
- Для turbine_2 подтверждено совпадение ячеек всех трёх моделей; кэш общий, координаты отдельные.
- Live-погода, полученная после фиксированного D 12:00, не доказывает доступность до выпуска:
  такой запрос получает 503. Исторические выпуски используйте с previous_runs.

## Проверка

Из ML/:

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q tests
python -m windml.task_report
```

Тесты читают внешний YAML-контракт и проверяют 54 февральских выпуска, UTC, интервалы и защиту от утечки.
Полные результаты в outputs/task_report/. Покрытие P10–P90 на январе — 76,7–83,9%.
Цель B4 по частоте warning 20–35% пока не достигнута: 59–70%; объяснение в TRAINING.md.
