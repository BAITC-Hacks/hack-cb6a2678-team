"""Загрузка и очистка SCADA-данных турбины."""
from __future__ import annotations

import pandas as pd

from .config import SITES, LOCAL_TZ
from . import config

COLS = ["id", "time", "ws_obs", "power", "temp_obs"]


def load_scada(site: str = "turbine_1", path=None) -> pd.DataFrame:
    """10-минутные данные → DataFrame с колонками time, ws_obs, power, temp_obs."""
    path = path or SITES[site].scada_csv
    df = pd.read_csv(path)
    df.columns = COLS[: len(df.columns)]
    df["time"] = pd.to_datetime(df["time"])
    df = df.drop(columns=["id"]).sort_values("time").drop_duplicates("time")
    df["power"] = df["power"].clip(0, 1)
    return df


def to_hourly(df: pd.DataFrame, min_samples: int = 4) -> pd.DataFrame:
    """Среднее за час [HH:00, HH:50]. Часы с <min_samples замерами отбрасываются."""
    g = df.set_index("time").resample("1h")
    h = g.mean(numeric_only=True)
    h["n"] = g["power"].count()
    h = h[h["n"] >= min_samples].drop(columns="n")
    h.index.name = "time"
    return h


def flag_anomalies(h: pd.DataFrame) -> pd.Series:
    """Часы с вероятным простоем/ограничением: ветер есть, мощности нет.

    Такие часы не несут информации о связи «погода → выработка», поэтому
    по умолчанию исключаются из обучения (но остаются в оценке качества).
    """
    return (h["power"] < 0.02) & (h["ws_obs"] > 6.0)


def load_hourly(site: str = "turbine_1", path=None, scada_tz=None) -> pd.DataFrame:
    df = load_scada(site, path)
    # Сначала UTC: переход официального времени не сдвигает приборные часы.
    df["time"] = df.time.dt.tz_localize(scada_tz or config.SCADA_TZ,
                                        ambiguous="NaT", nonexistent="NaT").dt.tz_convert("UTC")
    h = to_hourly(df.dropna(subset=["time"]))
    h.index = h.index.tz_convert(LOCAL_TZ).tz_localize(None)
    h = h[~h.index.duplicated(keep=False)]
    h["anomaly"] = flag_anomalies(h)
    return h
