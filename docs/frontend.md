# Фронтенд

React. Бэкенд уже отдаёт моки по финальному контракту — можно делать все экраны сейчас,
данные потом станут реальными без изменения формата.

- API: `http://localhost:8080/api/**`, CORS открыт.
- Контракт и примеры: `backend/src/main/resources/static/openapi.yaml`, Swagger UI — http://localhost:8080/swagger-ui.html.
  Удобно сгенерировать типы: `npx openapi-typescript <путь к openapi.yaml> -o src/api/types.ts`.
- Продуктовое описание экранов — `docs/overview.md`.

## Общие правила отображения

- Все времена приходят в **UTC**. Показывать в местном времени станции (Казахстан, UTC+5) с подписью, либо в UTC с подписью — главное, одинаково везде.
- Мощность — **доля от номинала 0..1**. Показывать в процентах (0.42 → 42 %). Не МВт.
- Диапазон дат для выбора — из `GET /api/meta` (`backtestFrom`…`backtestTo`), не хардкодить.
- `null` в `actualPower` — факт ещё неизвестен (не 0!). В `p10`/`p90` — интервала нет, просто не рисовать полосу.

## Экраны и эндпоинты

### 1. Прогноз

| Что | Откуда |
|---|---|
| Выбор даты, горизонт 24/48 | `GET /api/meta` |
| Выбор турбины | `GET /api/turbines` |
| График: линия `predictedPower`, полоса `p10`–`p90`, факт `actualPower` по галочке | `GET /api/forecast?turbineId&date&horizonHours&revision` → `points` |
| Плашки: средняя, пик и его время, часов простоя | `summary` (`meanPower`, `maxPower`, `maxPowerAt`, `lowPowerHours`) |
| Предупреждения | `alerts[]`: `type`, `severity` (info/warning/critical), `from`–`to` (включительно), `message` |
| Отчёт агента | `agentReport` |
| Переключатель версий 00/06/12/18 и изменение в % | `GET /api/forecast/revisions` → `revisions[]` (`changeVsPreviousPct`) |
| «Данные не из будущего» | `weatherIssuedAt` < `forecastIssuedAt`, показать оба времени |

По умолчанию `revision=1`. Галочку «Показать факт» по умолчанию лучше выключить — в момент прогноза факт неизвестен.

### 2. Как агент думал

| Что | Откуда |
|---|---|
| Лента шагов | `GET /api/agent-log?date&turbineId&revision` → `steps[]` |
| Статус шага | `status`: `success` ✓, `running` (спиннер), `retrying` (жёлтый), `failed` (красный) |
| Инструмент | `tool` (может быть `null`) |
| Отметка «✓ данные не из будущего» | у шага с `dataIssuedAt`: сравнить с `forecastIssuedAt` ответа |
| Кнопка «Пересчитать» | `POST /api/forecast/run` `{turbineId, date, horizonHours}` → 202 |

После «Пересчитать» — поллинг `GET /api/agent-log?date&turbineId` раз в 1–2 с, пока все шаги не станут `success`
(в моках шаги завершаются по одному каждые 2 с).

### 3. Качество за февраль

| Что | Откуда |
|---|---|
| Главные цифры: наша ошибка vs бейзлайны | `GET /api/metrics?from&to[&turbineId]` → `mae`, `baselineMae`, `powerCurveBaselineMae` |
| «Во сколько раз точнее» | `baselineMae / mae` |
| Надёжность интервала | `intervalCoverage` (≈0.8 — хорошо) |
| Календарь-тепловая карта по дням | `byDay[]` (`date`, `mae`); клик → экран 1 на эту дату |
| График ошибки по горизонту | `byLeadTime[]` (`leadHour` 1..48, `mae`) |

`from`/`to` — из `GET /api/meta`. Без `turbineId` — по всем турбинам.

### 4. Чат с агентом

`POST /api/agent/chat` `{ "message": "..." }` → `{ answer, model, toolCalls[], durationMs }`.

- Ответ может идти **10–60 секунд** (локальная LLM) — нужен индикатор «агент думает…» и таймаут не меньше 5 минут.
- `answer` — markdown, рендерить как markdown.
- `toolCalls` — показать мелко под ответом («агент посмотрел: прогноз t1 на 05.02»).
- `503` — LLM не запущена, показать понятное сообщение.

## Ошибки

Все ошибки — `{ "message": "..." }`: `400` некорректные параметры, `404` неизвестная турбина, `503` LLM недоступна.
