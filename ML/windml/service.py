"""Typed backend-facing ML operations; model artifacts select their weather site."""
from __future__ import annotations

from datetime import date
from functools import lru_cache
from typing import Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field

from .analysis import analyze
from .config import CFG
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
    unit: str = "fraction_of_rated_power"
    model_trained_at: str | None
    weather_source: str
    weather_site: str
    weather_site_assumption: str | None = None
    weather_hash: str
    analysis: dict
    hourly: list[HourlyForecast]


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


def predict(request: ForecastRequest) -> ForecastResponse:
    model = get_model(request.site)
    data = model.meta.get("data", {})
    weather_site = data.get("weather_site", request.site)
    provider = get_provider(request.weather_source, weather_site)
    weather, meta = fetch_issue_weather(request.issue_date, provider, models=model.nwp_models)
    forecast = model.predict(weather)
    values = forecast[["forecast", "p10", "p50", "p90"]].to_numpy()
    if not np.isfinite(values).all() or (forecast.n_models < 1).any():
        raise WeatherUnavailable("Incomplete weather data for the requested 48-hour forecast")
    report = analyze(forecast, meta, model.meta, expected_hours=48)
    return ForecastResponse(
        site=request.site, issue_date=meta["issue_date"],
        target_start=meta["target_start"], target_end=meta["target_end"],
        model_trained_at=model.meta.get("trained_at"), weather_source=meta["source"],
        weather_site=weather_site, weather_site_assumption=data.get("weather_site_assumption"),
        weather_hash=meta["weather_hash"], analysis=report,
        hourly=[HourlyForecast(time=str(t), lead_day=int(row.lead_day),
                               **{c: round(float(row[c]), 6) for c in ("forecast", "p10", "p50", "p90")})
                for t, row in forecast.iterrows()],
    )
