"""Метрики качества (мощность нормирована на номинал → nMAE = MAE)."""
from __future__ import annotations

import numpy as np
import pandas as pd


def pinball(y, q_pred, q):
    d = y - q_pred
    return float(np.nanmean(np.maximum(q * d, (q - 1) * d)))


def score(y: pd.Series, pred: pd.DataFrame | pd.Series, col: str = "forecast") -> dict:
    p = pred[col] if isinstance(pred, pd.DataFrame) else pred
    m = y.notna() & p.notna()
    y, p = y[m], p[m]
    if len(y) == 0:
        return {"n": 0}
    e = p - y
    out = {
        "n": int(len(y)),
        "nMAE_%": round(100 * float(np.abs(e).mean()), 2),
        "nRMSE_%": round(100 * float(np.sqrt((e ** 2).mean())), 2),
        "bias_%": round(100 * float(e.mean()), 2),
        "corr": round(float(np.corrcoef(y, p)[0, 1]), 3) if len(y) > 2 and p.std() > 0 and y.std() > 0 else None,
    }
    if isinstance(pred, pd.DataFrame) and {"p10", "p90"} <= set(pred.columns):
        lo, hi = pred.loc[y.index, "p10"], pred.loc[y.index, "p90"]
        out["coverage_10_90_%"] = round(100 * float(((y >= lo) & (y <= hi)).mean()), 1)
        out["pinball_avg"] = round(np.mean([pinball(y, pred.loc[y.index, f"p{int(q*100)}"], q)
                                             for q in (0.1, 0.5, 0.9)]), 4)
    return out
