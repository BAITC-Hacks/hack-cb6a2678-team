import numpy as np
import pandas as pd
import pytest

from windml.features import build_features
from windml.model import WindPowerModel


def weather_frame(days=100):
    idx = pd.date_range("2025-01-01", periods=days * 24, freq="h")
    return pd.DataFrame({
        "lead_day": 1,
        "test__wind_speed_100m": 5 + 3 * np.sin(np.arange(len(idx)) / 12),
        "test__temperature_2m": 10.,
    }, index=idx)


def test_training_features_match_daily_forecast_features():
    nwp = weather_frame(4)
    nwp = pd.concat([nwp, nwp.assign(lead_day=2)])
    batch = build_features(nwp, ["test"])
    for day in nwp.index.normalize().unique():
        for lead in (1, 2):
            mask = (nwp.index.normalize() == day) & (nwp.lead_day == lead)
            single = build_features(nwp[mask], ["test"])
            pd.testing.assert_frame_equal(batch[mask], single, check_freq=False)


def test_calibration_does_not_see_validation_or_future(monkeypatch):
    from windml import model as module

    nwp = weather_frame()
    scada = pd.DataFrame({"ws_obs": nwp["test__wind_speed_100m"],
                          "power": .3, "anomaly": False}, index=nwp.index)
    # Changing validation labels must not affect validation features/predictions.
    cut = nwp.index.max() - pd.Timedelta(days=20)
    calls = []
    original_fit = module.EmpiricalPowerCurve.fit

    def record_fit(self, ws, power, *args, **kwargs):
        calls.append(ws.index.max())
        return original_fit(self, ws, power, *args, **kwargs)

    class FakeRegressor:
        best_iteration_ = 100

        def fit(self, X, y, **kwargs):
            self.booster_ = self
            self.n_features = X.shape[1]
            return self

        def predict(self, X):
            return X["pc_qm"].to_numpy()

        def feature_importance(self, kind):
            return np.ones(self.n_features)

    monkeypatch.setattr(module.EmpiricalPowerCurve, "fit", record_fit)
    monkeypatch.setattr(WindPowerModel, "_lgb", lambda *a, **kw: FakeRegressor())
    first = WindPowerModel(["test"]).fit(nwp, scada, val_days=20)
    changed = scada.copy()
    changed.loc[changed.index > cut, "power"] = .9
    changed.loc[changed.index > cut, "ws_obs"] *= 2
    changed.loc[pd.Timestamp("2030-01-01")] = [100., 1., False]
    second = WindPowerModel(["test"]).fit(nwp, changed, val_days=20)
    assert calls == [cut, nwp.index.max(), cut, nwp.index.max()]
    np.testing.assert_allclose(first.val_predictions_["forecast"],
                               second.val_predictions_["forecast"])
    # Final production calibration should use the full supplied period.
    assert not np.array_equal(first.qmap_.dst_q_, second.qmap_.dst_q_)


def test_short_training_period_has_clear_error():
    nwp = weather_frame(3)
    scada = pd.DataFrame({"ws_obs": 5., "power": .3, "anomaly": False}, index=nwp.index)
    with pytest.raises(ValueError, match="training and validation periods"):
        WindPowerModel(["test"]).fit(nwp, scada)
