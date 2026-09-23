"""Замороженные поправки из валидации, ни один параметр не выбирается на январе."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .metrics import score


def apply_calibration(frame, settings):
    out = frame.copy()
    for lead, parameters in settings.get("by_lead_day", {}).items():
        mask = out.lead_day == int(lead)
        for column in ("forecast", "mean", "p10", "p50", "p90"):
            if column in out:
                out.loc[mask, column] = (out.loc[mask, column] * parameters["scale"] + parameters["offset"]).clip(0, 1)
        padding = parameters.get("interval_padding", 0.)
        out.loc[mask, "p10"] = (out.loc[mask, "p10"] - padding).clip(0, 1)
        out.loc[mask, "p90"] = (out.loc[mask, "p90"] + padding).clip(0, 1)
    out["p10"] = np.minimum(out.p10, out.forecast)
    out["p90"] = np.maximum(out.p90, out.forecast)
    return out


def fit_bias(validation):
    if pd.Timestamp(validation.index.max()) >= pd.Timestamp("2026-01-01"):
        raise ValueError("January must not be used to select calibration")
    # Поправки обучаются на ноябре, метод выбирается на декабре.
    fitting = validation[validation.index < "2025-12-01"]
    selection = validation[validation.index >= "2025-12-01"]
    if fitting.empty or selection.empty:
        raise ValueError("November fitting and December selection periods are required")
    candidates, metrics = {}, {}
    for method in ("none", "additive", "scale", "affine"):
        parameters = {}
        for lead, group in fitting.groupby("lead_day"):
            x, y = group.forecast.to_numpy(), group.actual.to_numpy()
            a, b = 1., 0.
            if method == "additive":
                b = float(np.mean(y - x))
            elif method == "scale":
                a = float(np.dot(x, y) / max(np.dot(x, x), 1e-12))
            elif method == "affine":
                a, b = np.linalg.lstsq(np.column_stack([x, np.ones(len(x))]), y, rcond=None)[0]
                a = max(0., float(a))
                b = float(b)
            parameters[str(int(lead))] = {"scale": a, "offset": b}
        settings = {"by_lead_day": parameters}
        adjusted = apply_calibration(selection, settings).reset_index(drop=True)
        candidates[method] = settings
        metrics[method] = score(adjusted.actual, adjusted)
    chosen = min(metrics, key=lambda name: metrics[name]["nMAE_%"])
    return {**candidates[chosen], "method": chosen, "fit_period": [str(fitting.index.min()), str(fitting.index.max())],
            "selection_period": [str(selection.index.min()), str(selection.index.max())], "candidate_metrics": metrics}


def fit_intervals(validation, settings):
    if pd.Timestamp(validation.index.max()) >= pd.Timestamp("2026-01-01"):
        raise ValueError("January must not be used to calibrate intervals")
    adjusted = apply_calibration(validation[validation.index >= "2025-12-01"], settings)
    for lead, group in adjusted.groupby("lead_day"):
        residual = np.maximum(group.p10 - group.actual, group.actual - group.p90).to_numpy()
        rank = min(len(residual), int(np.ceil((len(residual) + 1) * .8)))
        padding = max(0., float(np.sort(residual)[rank - 1]))
        settings["by_lead_day"][str(int(lead))]["interval_padding"] = padding
    settings["interval_method"] = "per-lead split residual quantile, nominal 80%; December 2025 only"
    settings["interval_period"] = [str(adjusted.index.min()), str(adjusted.index.max())]
    return settings


def fit_warning_thresholds(validation):
    if pd.Timestamp(validation.index.max()) >= pd.Timestamp("2026-01-01"):
        raise ValueError("January must not be used to choose warning thresholds")
    frame = validation.copy()
    frame["issue"] = frame.index.normalize() - pd.to_timedelta(frame.lead_day, unit="D")
    frame["width"] = frame.p90 - frame.p10
    frame["gap"] = (frame.forecast - frame.pc_baseline).abs()
    stats = frame.groupby("issue").agg(spread=("ens_ws_std", "max"), width=("width", "mean"),
                                      gap=("gap", "mean"), n=("forecast", "count"))
    stats = stats[stats.n == 48]
    if stats.empty:
        raise ValueError("Complete validation issues required for warning thresholds")
    # Квантили заданы до просмотра февраля: три редких события дают полезную частоту статуса.
    thresholds = {"HIGH_NWP_DISAGREEMENT": float(stats.spread.quantile(.9)),
                  "WIDE_INTERVAL": float(stats.width.quantile(.9)),
                  "ML_VS_PHYSICS_GAP": float(stats.gap.quantile(.95))}
    rates = {code: float((stats[column] > thresholds[code]).mean())
             for code, column in zip(thresholds, ("spread", "width", "gap"))}
    return {"thresholds": thresholds, "quantiles": [.9, .9, .95],
            "period": [str(validation.index.min()), str(validation.index.max())],
            "n_issues": len(stats), "validation_issue_rates": rates}
