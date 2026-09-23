"""REST-сервер над инструментами windml — для агента на Java/Spring AI.

Запуск:  uvicorn windml.api:app --port 8000
Документация (Swagger): http://localhost:8000/docs

Каждый инструмент доступен как POST /tools/<name> с JSON-телом аргументов.
Ответ — тот же JSON, что возвращает windml.tools.call_tool.
"""
from __future__ import annotations

import json

from fastapi import Body, FastAPI, HTTPException
from fastapi.responses import JSONResponse

from .tools import TOOL_SCHEMAS, call_tool
from . import service
from .service import ForecastRequest, ForecastResponse, SiteName
from .weather import WeatherUnavailable

app = FastAPI(title="windml — прогноз выработки ВЭС", version="1.0.0")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/v1/models")
def models():
    return {"models": [{"site": site, "available": service.model_path(site).exists()}
                       for site in ("turbine_1", "turbine_2")]}


@app.get("/v1/models/{site}")
def model_info(site: SiteName):
    try:
        return {"site": site, **service.get_model(site).meta}
    except FileNotFoundError as exc:
        raise HTTPException(503, "Model has not been trained yet") from exc


@app.post("/v1/forecast", response_model=ForecastResponse)
def forecast(request: ForecastRequest):
    try:
        return service.predict(request)
    except FileNotFoundError as exc:
        raise HTTPException(503, "Model has not been trained yet") from exc
    except WeatherUnavailable as exc:
        raise HTTPException(503, str(exc)) from exc


@app.get("/tools")
def list_tools():
    """JSON-схемы всех инструментов (можно отдать LLM как есть)."""
    return TOOL_SCHEMAS


@app.post("/tools/{name}")
def run_tool(name: str, args: dict = Body(default={})):
    res = json.loads(call_tool(name, args))
    return JSONResponse(res, status_code=400 if "error" in res else 200)
