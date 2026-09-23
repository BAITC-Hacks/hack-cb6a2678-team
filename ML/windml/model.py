"""Гибридная модель: физика (кривая мощности) + градиентный бустинг (LightGBM).

Пайплайн:
  NWP нескольких моделей → признаки → квантильное отображение ветра на SCADA →
  эмпирическая кривая мощности (физ. признак) → LightGBM (mean + квантили 10/50/90).
"""
from __future__ import annotations

import json
import logging
import warnings
from datetime import datetime, timezone
from pathlib import Path

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd

from . import __version__
from .config import CFG
from .features import build_features
from .metrics import score
from .powercurve import EmpiricalPowerCurve, QuantileMapper

log = logging.getLogger(__name__)
warnings.filterwarnings("ignore", category=FutureWarning, module="lightgbm")
warnings.filterwarnings("ignore", message=".*eval_set.*")

BASE_PARAMS = dict(learning_rate=0.03, num_leaves=31, min_child_samples=40,
                   feature_fraction=0.8, bagging_fraction=0.8, bagging_freq=1,
                   lambda_l2=1.0, verbose=-1, n_jobs=-1)


class WindPowerModel:
    def __init__(self, nwp_models=None, quantiles=None, drop_anomalies=True):
        self.nwp_models = list(nwp_models or CFG.nwp_models)
        self.quantiles = tuple(quantiles or CFG.quantiles)
        self.drop_anomalies = drop_anomalies
        self.meta: dict = {}

    # -------------------------------------------------------------- features
    def _features(self, nwp: pd.DataFrame) -> pd.DataFrame:
        X = build_features(nwp, self.nwp_models)
        X["ws_qm"] = self.qmap_(X["ens_ws_mean"].to_numpy())
        X["pc_qm"] = self.pcurve_(X["ws_qm"])
        # ожидаемая мощность с учётом разброса ансамбля (усреднение кривой по ±σ)
        s = X["ens_ws_std"].fillna(0).to_numpy()
        ws_q = X["ws_qm"].to_numpy()
        X["pc_qm_smooth"] = np.mean([self.pcurve_(np.clip(ws_q + k * s, 0, None))
                                     for k in (-1, -0.5, 0, 0.5, 1)], axis=0)
        for m in self.nwp_models:
            if f"{m}_ws_hub" in X:
                X[f"{m}_pc"] = self.pcurve_(self.qmap_(X[f"{m}_ws_hub"].to_numpy()))
        return X.reindex(columns=self.features_) if hasattr(self, "features_") else X

    def _monotone(self, cols):
        return [1 if c in ("pc_qm", "pc_qm_smooth") else 0 for c in cols]

    # ------------------------------------------------------------------- fit
    def fit(self, nwp_train: pd.DataFrame, scada_hourly: pd.DataFrame, val_days: int = 60):
        y_all = scada_hourly["power"].reindex(nwp_train.index)
        anom = scada_hourly["anomaly"].reindex(nwp_train.index).fillna(False).astype(bool)
        raw = build_features(nwp_train, self.nwp_models)
        ok = y_all.notna() & raw["ens_ws_mean"].notna()
        ws_obs = scada_hourly["ws_obs"].reindex(nwp_train.index)
        train_mask = ok & (~anom if self.drop_anomalies else True)
        if not train_mask.any():
            raise ValueError("No usable training rows")
        cut = nwp_train.index[train_mask].max() - pd.Timedelta(days=val_days)
        if not (train_mask & (nwp_train.index <= cut)).any() or not (
                train_mask & (nwp_train.index > cut)).any():
            raise ValueError("Need nonempty training and validation periods; provide more data")

        # Fit every learned transformation on the training partition only.
        # SCADA outside the supplied NWP period must never enter calibration.
        def fit_calibration(mask):
            times = nwp_train.index[mask].unique()
            clean = scada_hourly.loc[scada_hourly.index.isin(times) & ~scada_hourly["anomaly"]]
            self.pcurve_ = EmpiricalPowerCurve().fit(clean["ws_obs"], clean["power"])
            self.qmap_ = QuantileMapper().fit(raw.loc[mask, "ens_ws_mean"], ws_obs[mask])

        fit_calibration(ok & (nwp_train.index <= cut))

        X = self._features(nwp_train)
        X, y = X[train_mask], y_all[train_mask]
        self.features_ = list(X.columns)
        log.info("train rows=%d features=%d", len(X), len(self.features_))

        # временная валидация: последние val_days суток
        tr = X.index <= cut
        va = X.index > cut
        Xall = self._features(nwp_train)
        va_all = (ok & (nwp_train.index > cut)).to_numpy()   # честная оценка: включая простои
        Xv, yv = Xall[va_all], y_all[va_all]
        best_it, val_pred = {}, {}
        for name, obj in self._objectives():
            m = self._lgb(obj).fit(X[tr], y[tr], eval_set=[(X[va], y[va])],
                                   callbacks=[lgb.early_stopping(100, verbose=False)])
            best_it[name] = max(int(m.best_iteration_ or 500), 100)
            val_pred[name] = m.predict(Xv)
        vp = self._postprocess(pd.DataFrame(val_pred, index=Xv.index))
        vp.index = pd.RangeIndex(len(vp)); yv = yv.reset_index(drop=True)
        lead = Xv["lead_day"].to_numpy()
        self.meta["validation"] = {
            "method": "temporal holdout used for early stopping; preprocessing fit on training partition only",
            "period": [str((cut + pd.Timedelta(hours=1)).date()), str(X.index.max().date())],
            "model": score(yv, vp),
            "baseline_power_curve": score(yv, pd.Series(Xv["pc_qm"].to_numpy())),
            "baseline_climatology": score(yv, pd.Series(float(y[tr].mean()), index=yv.index)),
            "by_lead_day": {int(l): score(yv[lead == l], vp[lead == l]) for l in np.unique(lead)},
        }
        self.val_predictions_ = vp.assign(y=yv, lead_day=lead, time_local=Xv.index)
        # финальное обучение на всех данных
        fit_calibration(ok)
        X = self._features(nwp_train)[train_mask]
        self.models_ = {n: self._lgb(o, int(best_it[n] * 1.1)).fit(X, y) for n, o in self._objectives()}
        imp = pd.Series(self.models_["mean"].booster_.feature_importance("gain"), index=self.features_)
        self.meta.update({
            "version": __version__,
            "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "train_period": [str(X.index.min().date()), str(X.index.max().date())],
            "n_train_rows": int(len(X)),
            "n_anomalies_dropped": int((ok & anom).sum()),
            "nwp_models": self.nwp_models,
            "leakage_mode": CFG.leakage_mode,
            "best_iterations": best_it,
            "top_features": (imp / imp.sum()).sort_values(ascending=False).head(10).round(3).to_dict(),
            "power_curve": self.pcurve_.to_dict(),
            "ws_train_range": [float(np.nanmin(X["ens_ws_mean"])), float(np.nanmax(X["ens_ws_mean"]))],
        })
        return self

    def _objectives(self):
        yield "mean", dict(objective="regression")
        for q in self.quantiles:
            yield f"p{int(round(q * 100))}", dict(objective="quantile", alpha=q)

    def _lgb(self, obj, n_estimators=3000):
        extra = {}
        if obj.get("objective") == "regression":   # LightGBM не поддерживает monotone для quantile
            extra["monotone_constraints"] = self._monotone(self.features_)
        return lgb.LGBMRegressor(n_estimators=n_estimators, **BASE_PARAMS, **obj, **extra)

    @staticmethod
    def _postprocess(df: pd.DataFrame) -> pd.DataFrame:
        df = df.clip(0, 1)
        qc = sorted([c for c in df.columns if c.startswith("p")], key=lambda c: int(c[1:]))
        if qc:
            df[qc] = np.sort(df[qc].to_numpy(), axis=1)  # устраняем пересечение квантилей
        df["forecast"] = df["mean"] if "mean" in df else df.get("p50")
        return df

    # --------------------------------------------------------------- predict
    def predict(self, nwp: pd.DataFrame) -> pd.DataFrame:
        X = self._features(nwp)
        out = pd.DataFrame({n: m.predict(X) for n, m in self.models_.items()}, index=X.index)
        out = self._postprocess(out)
        out["lead_day"] = nwp["lead_day"].to_numpy()
        out["pc_baseline"] = X["pc_qm"].to_numpy()
        out["ens_ws_mean"] = X["ens_ws_mean"].to_numpy()
        out["ens_ws_std"] = X["ens_ws_std"].to_numpy()
        out["n_models"] = X["n_models"].to_numpy()
        out.index.name = "time_local"
        return out

    # ---------------------------------------------------------------- io
    def save(self, path: Path | None = None) -> Path:
        path = Path(path or CFG.model_dir / "wind_model.joblib")
        path.parent.mkdir(parents=True, exist_ok=True)
        joblib.dump(self, path)
        path.with_suffix(".json").write_text(json.dumps(self.meta, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        return path

    @staticmethod
    def load(path: Path | None = None) -> "WindPowerModel":
        return joblib.load(Path(path or CFG.model_dir / "wind_model.joblib"))
