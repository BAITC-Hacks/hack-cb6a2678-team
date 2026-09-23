"""Инжиниринг признаков из NWP (одинаковый для обучения и инференса)."""
from __future__ import annotations

import numpy as np
import pandas as pd

R_DRY = 287.05


def _cols(df, var):
    return [c for c in df.columns if c.endswith(f"__{var}")]


def hub_wind(df: pd.DataFrame, model: str) -> pd.Series:
    """Ветер на высоте ступицы: 100 м если есть, иначе 10 м по степенному закону."""
    c100, c10 = f"{model}__wind_speed_100m", f"{model}__wind_speed_10m"
    ws10 = df[c10] * (100 / 10) ** 0.14 if c10 in df else np.nan
    if c100 in df:
        return df[c100].fillna(ws10) if c10 in df else df[c100]
    return ws10 if c10 in df else pd.Series(np.nan, index=df.index)


def build_features(nwp: pd.DataFrame, models: list[str]) -> pd.DataFrame:
    """nwp: index = местное время цели, колонки lead_day + <model>__<var>."""
    X = pd.DataFrame(index=nwp.index)
    X["lead_day"] = nwp["lead_day"].astype(float)
    hubs = {}
    for m in models:
        if not any(c.startswith(f"{m}__") for c in nwp.columns):
            continue
        ws = hub_wind(nwp, m)
        hubs[m] = ws
        X[f"{m}_ws_hub"] = ws
        if f"{m}__wind_speed_10m" in nwp:
            X[f"{m}_ws10"] = nwp[f"{m}__wind_speed_10m"]
            if f"{m}__wind_speed_100m" in nwp:
                X[f"{m}_shear"] = np.log(nwp[f"{m}__wind_speed_100m"].clip(0.3) /
                                         nwp[f"{m}__wind_speed_10m"].clip(0.3)) / np.log(10)
        for lvl in ("100m", "10m"):
            c = f"{m}__wind_direction_{lvl}"
            if c in nwp:
                rad = np.deg2rad(nwp[c])
                X[f"{m}_wd_sin"], X[f"{m}_wd_cos"] = np.sin(rad), np.cos(rad)
                break
        if f"{m}__wind_gusts_10m" in nwp:
            X[f"{m}_gust_ratio"] = nwp[f"{m}__wind_gusts_10m"] / nwp.get(f"{m}__wind_speed_10m", np.nan).clip(0.5)
    # ансамбль моделей: среднее, разброс (мера неопределённости)
    H = pd.DataFrame(hubs)
    X["ens_ws_mean"] = H.mean(axis=1)
    X["ens_ws_std"] = H.std(axis=1) if H.shape[1] > 1 else 0.0
    X["ens_ws_max"], X["ens_ws_min"] = H.max(axis=1), H.min(axis=1)
    X["n_models"] = H.notna().sum(axis=1)
    # временной контекст: сглаживание ошибок фазы (прогноз «сдвинут» на пару часов)
    # A forecast issue contains one day for each lead. Keep the same context
    # during training: never smooth across days from different issues.
    grp = X.groupby(["lead_day", X.index.normalize()])["ens_ws_mean"]
    for w in (3, 6):
        X[f"ens_ws_roll{w}"] = grp.transform(lambda s: s.rolling(w, center=True, min_periods=1).mean())
    X["ens_ws_diff"] = grp.diff().fillna(0)
    # плотность воздуха → поправка к энергии ветра
    T = nwp[_cols(nwp, "temperature_2m")].mean(axis=1)
    P = nwp[_cols(nwp, "surface_pressure")].mean(axis=1) if _cols(nwp, "surface_pressure") else np.nan
    X["temp"] = T
    X["rho"] = (P * 100) / (R_DRY * (T + 273.15)) if _cols(nwp, "surface_pressure") else np.nan
    X["wpd"] = 0.5 * X["rho"].fillna(1.1) * X["ens_ws_mean"] ** 3 / 1000  # кВт/м²
    if _cols(nwp, "relative_humidity_2m"):
        X["rh"] = nwp[_cols(nwp, "relative_humidity_2m")].mean(axis=1)
    # календарь
    t = X.index
    X["hour_sin"], X["hour_cos"] = np.sin(2 * np.pi * t.hour / 24), np.cos(2 * np.pi * t.hour / 24)
    X["doy_sin"], X["doy_cos"] = np.sin(2 * np.pi * t.dayofyear / 365.25), np.cos(2 * np.pi * t.dayofyear / 365.25)
    return X
