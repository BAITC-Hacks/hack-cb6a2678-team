"""Централизованная конфигурация. Всё, что может меняться, — здесь."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(os.environ.get("WINDML_ROOT", Path(__file__).resolve().parents[1]))


@dataclass(frozen=True)
class Site:
    name: str
    lat: float
    lon: float
    scada_csv: Path


# Координаты турбины 1 — из ссылки в ТЗ (maps.app.goo.gl/iN6svMt69D5qRpFU9 → 43.645150, 78.535604).
SITES: dict[str, Site] = {
    "turbine_1": Site("turbine_1", 43.645150, 78.535604, ROOT / "data/raw/turbine_1.csv"),
    "turbine_2": Site("turbine_2", 43.643198, 78.538828, ROOT / "data/raw/turbine_2.csv"),
}

# Время в SCADA — местное (минимум температуры ~06:00, максимум ~15:00).
# Asia/Almaty учитывает переход Казахстана с UTC+6 на UTC+5 (01.03.2024).
LOCAL_TZ = "Asia/Almaty"
# Отдельный пояс приборных часов; выбирается на валидации до января.
SCADA_TZ = "Etc/GMT-5"
# Все переменные трёх моделей совпали: outputs/selection/weather_site_comparison.json.
WEATHER_CACHE_SITES = {"turbine_2": "turbine_1"}


@dataclass
class ForecastConfig:
    # Прогноз выпускается в день D в issue_hour (местное время) и покрывает
    # сутки D+1 и D+2 (00:00..23:00) → 48 почасовых значений (горизонт 12–60 ч).
    issue_hour: int = 12
    horizon_days: int = 2
    # "strict":   для суток D+k берём прогноз погоды previous_day{k+1}
    #             → гарантированно без утечки будущего.
    # "standard": previous_day{k} — точнее, но на границе допустимого.
    leakage_mode: str = "strict"
    nwp_models: list = field(default_factory=lambda: ["ecmwf_ifs025", "gfs_seamless", "icon_seamless"])
    hourly_vars: list = field(default_factory=lambda: [
        "wind_speed_10m", "wind_direction_10m",
        "wind_speed_100m", "wind_direction_100m",
        "wind_gusts_10m", "temperature_2m",
        "surface_pressure", "relative_humidity_2m",
    ])
    quantiles: tuple = (0.1, 0.5, 0.9)
    train_start: str = "2024-01-01"   # архив previous runs Open-Meteo доступен с 01.2024
    train_end: str = "2026-01-31"
    cache_dir: Path = ROOT / "data/cache"
    model_dir: Path = ROOT / "models"
    output_dir: Path = ROOT / "outputs"
    http_timeout: int = 60
    http_retries: int = 4


CFG = ForecastConfig()


def lead_to_previous_day(lead_day: int, mode: str | None = None) -> int:
    mode = mode or CFG.leakage_mode
    if mode == "strict":
        return lead_day + 1
    if mode == "standard":
        return lead_day
    raise ValueError(f"unknown leakage_mode={mode}")
