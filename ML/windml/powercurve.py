"""Физически-информированные компоненты: эмпирическая кривая мощности и
квантильное отображение ветра NWP → ветер на гондоле (SCADA)."""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression


class EmpiricalPowerCurve:
    """Кривая мощности турбины по SCADA: медиана по бинам 0.5 м/с + изотоническая
    монотонность (мощность не убывает с ростом ветра до номинала)."""

    def fit(self, ws: pd.Series, p: pd.Series, bin_w: float = 0.5, min_count: int = 20):
        bins = np.arange(0, ws.max() + bin_w, bin_w)
        cat = pd.cut(ws, bins)
        g = pd.DataFrame({"ws": ws, "p": p}).groupby(cat, observed=True)
        stat = g.agg(ws=("ws", "mean"), p=("p", "median"), n=("p", "size"))
        stat = stat[stat.n >= min_count]
        iso = IsotonicRegression(y_min=0, y_max=1, out_of_bounds="clip").fit(stat.ws, stat.p)
        self.x_ = stat.ws.to_numpy()
        self.y_ = iso.predict(self.x_)
        return self

    def __call__(self, ws) -> np.ndarray:
        ws = np.asarray(ws, float)
        out = np.interp(ws, self.x_, self.y_, left=0.0, right=self.y_[-1])
        return np.where(np.isnan(ws), np.nan, out)

    def to_dict(self):
        return {"ws": self.x_.round(2).tolist(), "p": self.y_.round(3).tolist()}


class QuantileMapper:
    """Переводит распределение ветра NWP в распределение ветра SCADA.

    Устраняет систематическую ошибку модели погоды (высота, рельеф, сетка 25 км)
    без предположений о профиле ветра."""

    def fit(self, src: pd.Series, dst: pd.Series, n_q: int = 201):
        q = np.linspace(0, 1, n_q)
        self.src_q_ = np.nanquantile(src, q)
        self.dst_q_ = np.nanquantile(dst, q)
        return self

    def __call__(self, x) -> np.ndarray:
        x = np.asarray(x, float)
        out = np.interp(x, self.src_q_, self.dst_q_)
        # линейная экстраполяция за пределы обучающего диапазона
        hi = x > self.src_q_[-1]
        out[hi] = self.dst_q_[-1] + (x[hi] - self.src_q_[-1])
        return np.where(np.isnan(x), np.nan, out)
