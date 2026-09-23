import pandas as pd

from windml.data import load_hourly
from windml.timeutils import utc_string


def test_scada_timezone_is_separate_from_issue_timezone(tmp_path):
    path = tmp_path / "scada.csv"
    pd.DataFrame({"id": range(6), "time": pd.date_range("2025-01-01 12:00", periods=6, freq="10min"),
                  "wind": 8., "power": .4, "temp": 0.}).to_csv(path, index=False)
    six = load_hourly(path=path, scada_tz="Etc/GMT-6")
    five = load_hourly(path=path, scada_tz="Etc/GMT-5")
    assert six.index[0] == pd.Timestamp("2025-01-01 11:00")
    assert five.index[0] == pd.Timestamp("2025-01-01 12:00")
    assert utc_string(six.index[0]) == "2025-01-01T06:00:00Z"


def calibration_data():
    import numpy as np
    index = pd.date_range("2025-11-01", "2025-12-31 23:00", freq="h")
    actual = .4 + .2 * np.sin(np.arange(len(index)))
    return pd.DataFrame({"actual": actual, "forecast": actual + .1, "p10": actual + .05,
                         "p50": actual + .1, "p90": actual + .15, "lead_day": 1}, index=index)


def test_bias_is_selected_before_january_and_reduces_known_bias():
    import pytest
    from windml.calibration import fit_bias, apply_calibration
    data = calibration_data()
    settings = fit_bias(data)
    after = apply_calibration(data, settings)
    assert abs((after.forecast - after.actual).mean()) < .001
    data.index = data.index + pd.Timedelta(days=61)
    with pytest.raises(ValueError, match="January"):
        fit_bias(data)


def test_interval_calibration_covers_validation_and_keeps_order():
    import numpy as np
    from windml.calibration import fit_intervals, apply_calibration
    data = calibration_data()
    data["actual"] += np.random.default_rng(42).normal(0, .12, len(data))
    settings = fit_intervals(data, {"by_lead_day": {"1": {"scale": 1., "offset": 0.}}})
    adjusted = apply_calibration(data.loc["2025-12-01":], settings)
    assert ((adjusted.actual >= adjusted.p10) & (adjusted.actual <= adjusted.p90)).mean() >= .79
    assert (adjusted.p10 <= adjusted.p50).all() and (adjusted.p50 <= adjusted.p90).all()
    assert (adjusted.p10 <= adjusted.forecast).all() and (adjusted.forecast <= adjusted.p90).all()


def test_warning_thresholds_reject_test_period():
    import pytest
    from windml.calibration import fit_warning_thresholds
    data = calibration_data()
    data.index = data.index + pd.Timedelta(days=61)
    with pytest.raises(ValueError, match="January"):
        fit_warning_thresholds(data)


def test_turbine_two_uses_verified_same_grid_offline(monkeypatch):
    from windml.config import SITES
    from windml.weather import PreviousRunsProvider, fetch_issue_weather
    monkeypatch.setenv("WINDML_OFFLINE", "1")
    assert (SITES["turbine_2"].lat, SITES["turbine_2"].lon) == (43.643198, 78.538828)
    first, _ = fetch_issue_weather("2026-01-31", PreviousRunsProvider("turbine_1"))
    second, _ = fetch_issue_weather("2026-01-31", PreviousRunsProvider("turbine_2"))
    pd.testing.assert_frame_equal(first, second)
