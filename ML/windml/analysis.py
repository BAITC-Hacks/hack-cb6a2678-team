"""Автоматический анализ результата прогноза — «глаза» агента.

Возвращает статус (ok / warning / error), список проверок и рекомендацию,
на основании которой агент решает: принять прогноз, пересчитать при
обновлении погоды или переключиться на запасной источник."""
from __future__ import annotations

import numpy as np
import pandas as pd
from .timeutils import utc_string

HIGH_SPREAD_MS = 2.5    # разброс ветра между NWP-моделями, м/с
RAMP_THRESHOLD = 0.30   # изменение мощности за 1 час (доля номинала)


def analyze(fc: pd.DataFrame, meta: dict | None = None, model_meta: dict | None = None,
            expected_hours: int = 48) -> dict:
    meta, model_meta = meta or {}, model_meta or {}
    issues, info = [], []
    thresholds = model_meta.get("warning_calibration", {}).get("thresholds", {})
    spread_threshold = thresholds.get("HIGH_NWP_DISAGREEMENT", HIGH_SPREAD_MS)

    def add(level, code, msg, mask=None):
        destination = issues if level != "info" else info
        groups = [None]
        if mask is not None:
            times = fc.index[np.asarray(mask)]
            groups = []
            for t in times:
                if not groups or t - groups[-1][-1] != pd.Timedelta(hours=1):
                    groups.append([t])
                else:
                    groups[-1].append(t)
        for group in groups:
            item = {"level": level, "code": code, "message": msg}
            if group is not None:
                item.update(from_utc=utc_string(group[0]), to_utc=utc_string(group[-1]))
            destination.append(item)

    n = len(fc)
    if n != expected_hours:
        add("critical", "WRONG_LENGTH", f"{n} часов вместо {expected_hours}")
    nan = int(fc["forecast"].isna().sum())
    if nan:
        add("critical", "NAN_VALUES", f"{nan} часов без прогноза (нет погодных данных)", fc["forecast"].isna())
    if ((fc["forecast"] < 0) | (fc["forecast"] > 1)).any():
        add("critical", "OUT_OF_RANGE", "значения вне [0, 1]", (fc["forecast"] < 0) | (fc["forecast"] > 1))
    if {"p10", "p90"} <= set(fc.columns) and (fc["p10"] > fc["p90"] + 1e-9).any():
        add("critical", "QUANTILE_CROSSING", "p10 > p90", fc["p10"] > fc["p90"])

    failed = meta.get("models_failed") or {}
    if failed:
        add("warning", "NWP_MODEL_MISSING", f"недоступны модели погоды: {', '.join(failed)}")
    nm = fc.get("n_models")
    if nm is not None and (nm < 2).mean() > 0.25:
        add("warning", "SINGLE_NWP", "в >25% часов доступна только одна модель погоды")

    spread = fc.get("ens_ws_std")
    if spread is not None:
        hi = int((spread > spread_threshold).sum())
        if hi:
            add("warning", "HIGH_NWP_DISAGREEMENT",
                f"{hi} ч с разбросом ветра между моделями > {spread_threshold:.2f} м/с — "
                f"рекомендуется пересчёт после следующего обновления прогноза погоды", spread > spread_threshold)

    lo_ws, hi_ws = model_meta.get("ws_train_range", [0, 99])
    ws = fc.get("ens_ws_mean")
    if ws is not None and ((ws > hi_ws) | (ws < lo_ws)).any():
        add("warning", "OUT_OF_DISTRIBUTION", f"ветер вне диапазона обучения [{lo_ws:.1f}; {hi_ws:.1f}] м/с", (ws > hi_ws) | (ws < lo_ws))

    ramps = fc["forecast"].diff().abs()
    ramp_hours = [str(t) for t in fc.index[ramps > RAMP_THRESHOLD]]
    if ramp_hours:
        add("info", "RAMP_EVENTS", f"резкие изменения мощности (>{RAMP_THRESHOLD:.0%}/ч)", ramps > RAMP_THRESHOLD)
    width = (fc["p90"] - fc["p10"]).mean() if "p90" in fc else np.nan
    if width > thresholds.get("WIDE_INTERVAL", 0.45):
        add("warning", "WIDE_INTERVAL", f"средняя ширина интервала p10–p90 = {width:.2f}", np.ones(n, dtype=bool))
    if "pc_baseline" in fc:
        gap = float((fc["forecast"] - fc["pc_baseline"]).abs().mean())
        if gap > thresholds.get("ML_VS_PHYSICS_GAP", 0.2):
            add("warning", "ML_VS_PHYSICS_GAP", f"ML-прогноз сильно отличается от физической кривой мощности ({gap:.2f})", np.ones(n, dtype=bool))

    daily = fc.groupby(fc.index.date)["forecast"].agg(["mean", "max"])
    stats = {
        "mean_power": round(float(fc["forecast"].mean()), 3),
        "capacity_factor_by_day": {str(k): round(float(v), 3) for k, v in daily["mean"].items()},
        "energy_capacity_hours": round(float(fc["forecast"].sum()), 2),
        "peak_hour": str(fc["forecast"].idxmax()) if n else None,
        "peak_value": round(float(fc["forecast"].max()), 3) if n else None,
        "hours_below_5pct": int((fc["forecast"] < 0.05).sum()),
        "hours_above_80pct": int((fc["forecast"] > 0.8).sum()),
        "mean_interval_width": round(float(width), 3) if width == width else None,
        "mean_nwp_spread_ms": round(float(spread.mean()), 2) if spread is not None else None,
    }
    levels = {i["level"] for i in issues}
    status = "critical" if "critical" in levels else "warning" if "warning" in levels else "ok"
    codes = {i["code"] for i in issues}
    if "critical" in levels:
        action = "rerun_with_fallback"      # сменить источник/модель погоды и пересчитать
    elif codes & {"HIGH_NWP_DISAGREEMENT", "NWP_MODEL_MISSING", "SINGLE_NWP"}:
        action = "accept_and_recheck_on_update"
    else:
        action = "accept"
    return {"status": status, "recommended_action": action, "issues": issues, "info": info, "stats": stats}
