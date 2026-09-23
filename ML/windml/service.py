"""Typed backend-facing ML operations; model artifacts select their weather site."""
from __future__ import annotations

from datetime import date
from functools import lru_cache
import hashlib
from typing import Literal

import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field

from .analysis import analyze
from .config import CFG, LOCAL_TZ
from .timeutils import utc_string
from .model import WindPowerModel
from .weather import WeatherUnavailable, fetch_issue_weather, get_provider

SiteName = Literal["turbine_1", "turbine_2"]


class ForecastRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    site: SiteName = "turbine_1"
    issue_date: date
    weather_source: Literal["previous_runs", "live"] = "previous_runs"


class HourlyForecast(BaseModel):
    time: str
    time_utc: str | None = None
    wind_speed_100m: float | None = None
    wind_spread: float | None = None
    temperature_2m: float | None = None
    lead_day: int
    forecast: float = Field(ge=0, le=1)
    p10: float = Field(ge=0, le=1)
    p50: float = Field(ge=0, le=1)
    p90: float = Field(ge=0, le=1)


class ForecastResponse(BaseModel):
    site: SiteName
    issue_date: str
    target_start: str
    target_end: str
    issued_at_utc: str | None = None
    local_tz: str | None = None
    target_start_utc: str | None = None
    target_end_utc: str | None = None
    weather_issued_before_utc: str | None = None
    leakage_mode: Literal["strict", "standard"] | None = None
    weather_models: list[str] | None = None
    model_version: str | None = None
    unit: str = "fraction_of_rated_power"
    model_trained_at: str | None
    weather_source: str
    weather_site: str
    weather_site_assumption: str | None = None
    weather_hash: str
    analysis: dict
    hourly: list[HourlyForecast]


class EvaluationRow(BaseModel):
    time_utc: str
    issue_date: str
    lead_day: Literal[1, 2]
    forecast: float
    p10: float
    p50: float
    p90: float
    actual: float | None
    pc_baseline: float
    anomaly: bool
    wind_speed_100m: float | None = None


class EvaluationResponse(BaseModel):
    site: SiteName
    model_version: str
    trained_until_utc: str
    period_start_utc: str
    period_end_utc: str
    rows: list[EvaluationRow]


def model_path(site):
    if site not in ("turbine_1", "turbine_2"):
        raise ValueError(f"Unknown site: {site}")
    filename = "wind_model.joblib" if site == "turbine_1" else "wind_model_turbine_2.joblib"
    return CFG.model_dir / filename


@lru_cache(maxsize=4)
def _load(path, modified_ns):
    return WindPowerModel.load(path)


def get_model(site):
    path = model_path(site)
    return _load(path, path.stat().st_mtime_ns)


@lru_cache(maxsize=8)
def _artifact_version(path, modified_ns):
    return "lgbm-" + hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def model_info(site, model=None, path=None):
    path = path or model_path(site)
    model = model or get_model(site)
    meta = model.meta
    end = pd.Timestamp(meta["train_period"][1]) + pd.Timedelta(hours=23)
    return {**meta, "site": site,
            "model_version": _artifact_version(path, path.stat().st_mtime_ns),
            "trained_until_utc": meta.get("trained_until_utc", utc_string(end)),
            "local_tz": meta.get("local_tz", LOCAL_TZ), "weather_models": model.nwp_models}


def finite_or_none(value):
    return round(float(value), 6) if pd.notna(value) and np.isfinite(value) else None


def evaluation(site):
    model_path(site)  # Проверяет имя до доступа к файлам.
    directory = CFG.output_dir / "training" / site
    artifact = directory / "evaluation_model.joblib"
    model = _load(artifact, artifact.stat().st_mtime_ns)
    info = model_info(site, model, artifact)
    frame = pd.read_csv(directory / "test_predictions.csv")
    if frame.empty:
        raise WeatherUnavailable("Evaluation predictions are empty")
    rows = []
    for row in frame.itertuples(index=False):
        local = pd.Timestamp(row.time_local)
        time_utc = getattr(row, "time_utc", utc_string(local, info["local_tz"]))
        rows.append(EvaluationRow(
            time_utc=time_utc, issue_date=str((local.normalize() - pd.Timedelta(days=row.lead_day)).date()),
            lead_day=int(row.lead_day),
            **{c: float(getattr(row, c)) for c in ("forecast", "p10", "p50", "p90", "pc_baseline")},
            actual=finite_or_none(row.actual), anomaly=bool(row.anomaly),
            wind_speed_100m=finite_or_none(row.ens_ws_mean)))
    start, end = min(r.time_utc for r in rows), max(r.time_utc for r in rows)
    if pd.Timestamp(info["trained_until_utc"]) >= pd.Timestamp(start):
        raise WeatherUnavailable("Evaluation model overlaps the test period")
    return EvaluationResponse(site=site, model_version=info["model_version"],
                              trained_until_utc=info["trained_until_utc"],
                              period_start_utc=start, period_end_utc=end, rows=rows)


def predict(request: ForecastRequest) -> ForecastResponse:
    model = get_model(request.site)
    data = model.meta.get("data", {})
    weather_site = data.get("weather_site", request.site)
    provider = get_provider(request.weather_source, weather_site)
    weather, meta = fetch_issue_weather(request.issue_date, provider, models=model.nwp_models,
                                       mode=model.meta.get("leakage_mode", "strict"))
    if pd.Timestamp(meta["weather_issued_before_utc"]) > pd.Timestamp(utc_string(meta["issue_time_local"])):
        raise WeatherUnavailable("Weather issuance bound exceeds the forecast issue time")
    forecast = model.predict(weather)
    values = forecast[["forecast", "p10", "p50", "p90"]].to_numpy()
    if not np.isfinite(values).all() or (forecast.n_models < 1).any():
        raise WeatherUnavailable("Incomplete weather data for the requested 48-hour forecast")
    report = analyze(forecast, meta, model.meta, expected_hours=48)
    return ForecastResponse(
        site=request.site, issue_date=meta["issue_date"],
        target_start=meta["target_start"], target_end=meta["target_end"],
        issued_at_utc=utc_string(meta["issue_time_local"]), local_tz=LOCAL_TZ,
        target_start_utc=utc_string(meta["target_start"]), target_end_utc=utc_string(meta["target_end"]),
        weather_issued_before_utc=meta["weather_issued_before_utc"], leakage_mode=meta["leakage_mode"],
        weather_models=meta["models_ok"], model_version=model_info(request.site, model)["model_version"],
        model_trained_at=model.meta.get("trained_at"), weather_source=meta["source"],
        weather_site=weather_site, weather_site_assumption=data.get("weather_site_assumption"),
        weather_hash=meta["weather_hash"], analysis=report,
        hourly=[HourlyForecast(time=str(t), time_utc=utc_string(t), lead_day=int(row.lead_day),
                               wind_speed_100m=finite_or_none(row.ens_ws_mean),
                               wind_spread=finite_or_none(row.ens_ws_std),
                               temperature_2m=finite_or_none(row.temperature_2m),
                               **{c: round(float(row[c]), 6) for c in ("forecast", "p10", "p50", "p90")})
                for t, row in forecast.iterrows()],
    )
