"""Интерфейс для AI-агента: функции-инструменты + JSON-схемы + диспетчер.

Все функции принимают и возвращают JSON-совместимые объекты (dict), компактные,
чтобы не перегружать контекст LLM. Полные ряды сохраняются в CSV, путь — в ответе.

Использование агентом:
    from windml.tools import TOOL_SCHEMAS, call_tool
    tools = TOOL_SCHEMAS                       # Anthropic tool-use формат
    tools = openai_schemas()                   # OpenAI / LangChain function-calling
    result_json = call_tool(name, arguments)   # всегда строка JSON, ошибки → {"error": ...}
"""
from __future__ import annotations

import json
import os
import traceback
from functools import lru_cache
from pathlib import Path

import pandas as pd

from . import pipeline
from .analysis import analyze
from .config import CFG
from .data import load_hourly
from .metrics import score
from .model import WindPowerModel
from .weather import fetch_issue_weather, get_provider

DEFAULT_SOURCE = os.environ.get("WINDML_WEATHER_SOURCE", "previous_runs")


@lru_cache(maxsize=4)
def _provider(source: str):
    if source == "mock":
        return get_provider("mock", hourly_scada=load_hourly())
    return get_provider(source)


_MODEL: WindPowerModel | None = None


def _model() -> WindPowerModel:
    global _MODEL
    if _MODEL is None:
        _MODEL = WindPowerModel.load()
    return _MODEL


# ------------------------------------------------------------------ tools
def train_model(start_date: str | None = None, end_date: str | None = None,
                weather_source: str = DEFAULT_SOURCE) -> dict:
    global _MODEL
    _MODEL = pipeline.train(provider=_provider(weather_source), start=start_date, end=end_date)
    m = _MODEL.meta
    return {"status": "trained", "train_period": m["train_period"], "n_train_rows": m["n_train_rows"],
            "validation": m["validation"], "top_features": m["top_features"]}


def get_weather_forecast(issue_date: str, weather_source: str = DEFAULT_SOURCE) -> dict:
    nwp, meta = fetch_issue_weather(issue_date, _provider(weather_source), models=_model().nwp_models)
    ws_cols = [c for c in nwp.columns if c.endswith("wind_speed_100m") or c.endswith("wind_speed_10m")]
    summary = nwp.groupby("lead_day")[ws_cols].mean().round(2).to_dict(orient="index")
    return {**meta, "mean_wind_by_lead_day": {int(k): v for k, v in summary.items()}}


def run_power_forecast(issue_date: str, weather_source: str = DEFAULT_SOURCE,
                       include_hourly: bool = True) -> dict:
    fc, meta, report = pipeline.forecast_issue(issue_date, _model(), _provider(weather_source))
    res = {"issue_date": meta["issue_date"], "target_start": meta["target_start"],
           "target_end": meta["target_end"], "weather_source": meta["source"],
           "weather_hash": meta["weather_hash"], "models_ok": meta["models_ok"],
           "file": meta["file"], "analysis": report}
    if include_hourly:
        res["hourly"] = [
            {"time": str(t), "lead_day": int(r.lead_day), "forecast": round(r.forecast, 3),
             "p10": round(r.p10, 3), "p90": round(r.p90, 3)}
            for t, r in fc.iterrows()]
    return res


def analyze_forecast(issue_date: str) -> dict:
    fp = CFG.output_dir / "forecasts" / f"forecast_{pd.Timestamp(issue_date).date()}.csv"
    if not fp.exists():
        return {"error": f"forecast for {issue_date} not found; call run_power_forecast first"}
    fc = pd.read_csv(fp, index_col=0, parse_dates=True)
    meta = json.loads(fp.with_suffix(".json").read_text(encoding="utf-8"))["meta"]
    return analyze(fc, meta, _model().meta, expected_hours=len(fc))


def check_weather_update(issue_date: str, previous_hash: str,
                         weather_source: str = DEFAULT_SOURCE) -> dict:
    _, meta = fetch_issue_weather(issue_date, _provider(weather_source), models=_model().nwp_models)
    changed = meta["weather_hash"] != previous_hash
    return {"updated": changed, "new_hash": meta["weather_hash"], "models_ok": meta["models_ok"],
            "recommendation": "rerun run_power_forecast" if changed else "no rerun needed"}


def evaluate_forecast(issue_date: str) -> dict:
    fp = CFG.output_dir / "forecasts" / f"forecast_{pd.Timestamp(issue_date).date()}.csv"
    if not fp.exists():
        return {"error": "forecast not found"}
    fc = pd.read_csv(fp, index_col=0, parse_dates=True)
    y = load_hourly()["power"].reindex(fc.index)
    if y.notna().sum() == 0:
        return {"status": "no_actuals", "message": "фактические данные за этот период ещё не поступили"}
    return {"overall": score(y, fc), "physics_baseline": score(y, fc["pc_baseline"]),
            "by_lead_day": {int(l): score(y[g.index], g) for l, g in fc.groupby("lead_day")}}


def run_backtest(start_date: str, end_date: str, weather_source: str = DEFAULT_SOURCE) -> dict:
    return pipeline.backtest(start_date, end_date, _model(), _provider(weather_source))


def get_model_info() -> dict:
    m = _model().meta
    keys = ["version", "trained_at", "train_period", "n_train_rows", "nwp_models", "leakage_mode",
            "weather_source", "validation", "top_features", "ws_train_range"]
    return {k: m.get(k) for k in keys}


# ---------------------------------------------------------------- schemas
_DATE = {"type": "string", "description": "Дата выпуска прогноза D в формате YYYY-MM-DD. "
                                          "Прогноз строится на сутки D+1 и D+2 (48 часов, местное время)."}
_SRC = {"type": "string", "enum": ["previous_runs", "live", "mock"],
        "description": "previous_runs — архив прогнозов погоды (бэктест, без утечки будущего); "
                       "live — свежий прогноз для работы в реальном времени; mock — только для тестов."}

TOOL_SCHEMAS = [
    {"name": "get_weather_forecast",
     "description": "Скачивает из Open-Meteo прогнозы погоды (несколько NWP-моделей), доступные на момент "
                    "выпуска issue_date, для координат ВЭС. Возвращает метаданные, средний ветер и weather_hash.",
     "input_schema": {"type": "object", "properties": {"issue_date": _DATE, "weather_source": _SRC},
                      "required": ["issue_date"]}},
    {"name": "run_power_forecast",
     "description": "Полный цикл: погода → признаки → ML-модель → почасовой прогноз выработки (доля номинала, "
                    "0..1) с интервалом p10–p90 на 48 часов + автоматический анализ качества. Сохраняет CSV.",
     "input_schema": {"type": "object", "properties": {
         "issue_date": _DATE, "weather_source": _SRC,
         "include_hourly": {"type": "boolean", "description": "Вернуть 48 почасовых значений (по умолчанию true)"}},
         "required": ["issue_date"]}},
    {"name": "analyze_forecast",
     "description": "Проверяет уже построенный прогноз: полнота, диапазоны, разброс моделей погоды, "
                    "рампы, выход за диапазон обучения. Возвращает status и recommended_action "
                    "(accept / accept_and_recheck_on_update / rerun_with_fallback).",
     "input_schema": {"type": "object", "properties": {"issue_date": _DATE}, "required": ["issue_date"]}},
    {"name": "check_weather_update",
     "description": "Проверяет, изменились ли входные погодные данные по сравнению с previous_hash. "
                    "Если updated=true — нужно заново вызвать run_power_forecast.",
     "input_schema": {"type": "object", "properties": {
         "issue_date": _DATE, "previous_hash": {"type": "string"}, "weather_source": _SRC},
         "required": ["issue_date", "previous_hash"]}},
    {"name": "evaluate_forecast",
     "description": "Сравнивает прогноз с фактической выработкой (если факт уже есть): nMAE, nRMSE, bias, "
                    "покрытие интервала, сравнение с физическим бейзлайном.",
     "input_schema": {"type": "object", "properties": {"issue_date": _DATE}, "required": ["issue_date"]}},
    {"name": "run_backtest",
     "description": "Скользящее воспроизведение прогнозов «как в прошлом»: выпуск каждый день с start_date "
                    "по end_date. Сохраняет все выпуски и итоговый почасовой ряд, считает метрики.",
     "input_schema": {"type": "object", "properties": {
         "start_date": {"type": "string"}, "end_date": {"type": "string"}, "weather_source": _SRC},
         "required": ["start_date", "end_date"]}},
    {"name": "train_model",
     "description": "Переобучает модель на исторических данных SCADA и архивных прогнозах погоды. "
                    "Долгая операция (скачивание ~2 лет погоды при первом запуске).",
     "input_schema": {"type": "object", "properties": {
         "start_date": {"type": "string"}, "end_date": {"type": "string"}, "weather_source": _SRC}}},
    {"name": "get_model_info",
     "description": "Информация о текущей модели: период обучения, метрики валидации, важные признаки.",
     "input_schema": {"type": "object", "properties": {}}},
]

_FUNCS = {f.__name__: f for f in (get_weather_forecast, run_power_forecast, analyze_forecast,
                                  check_weather_update, evaluate_forecast, run_backtest,
                                  train_model, get_model_info)}


def openai_schemas() -> list[dict]:
    return [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                              "parameters": t["input_schema"]}} for t in TOOL_SCHEMAS]


def call_tool(name: str, arguments: dict | str | None = None) -> str:
    """Единая точка входа для агента. Никогда не бросает исключение."""
    try:
        args = json.loads(arguments) if isinstance(arguments, str) else (arguments or {})
        if name not in _FUNCS:
            return json.dumps({"error": f"unknown tool {name}", "available": list(_FUNCS)})
        return json.dumps(_FUNCS[name](**args), ensure_ascii=False, default=str)
    except Exception as e:  # noqa: BLE001
        return json.dumps({"error": f"{type(e).__name__}: {e}",
                           "trace": traceback.format_exc(limit=3)[-800:]}, ensure_ascii=False)
