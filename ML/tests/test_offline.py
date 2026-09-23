"""Офлайн-тесты (без интернета): парсинг Open-Meteo, отсутствие утечки,
сквозной цикл на mock-погоде, контракт инструментов для агента.

Запуск: WINDML_ROOT=. python -m pytest -q tests
"""
import json

import numpy as np
import pandas as pd
import pytest

from windml import weather
from windml.analysis import analyze
from windml.config import lead_to_previous_day
from windml.weather import PreviousRunsProvider, select_leads, target_window


class FakeResp:
    def __init__(self, js, code=200):
        self._js, self.status_code, self.text = js, code, json.dumps(js)

    def json(self):
        return self._js

    def raise_for_status(self):
        pass


def fake_open_meteo(url, params, timeout):
    """Имитирует ответ previous-runs-api: для pdN значение = 10*N (легко проверить)."""
    t = pd.date_range(params["start_date"], pd.Timestamp(params["end_date"]) + pd.Timedelta(hours=23),
                      freq="1h", tz="UTC")
    hourly = {"time": t.strftime("%Y-%m-%dT%H:%M").tolist()}
    for key in params["hourly"].split(","):
        if key.startswith("wind_speed_100m") and params["models"] == "gfs_seamless":
            return FakeResp({"error": True, "reason": f"Variable {key} not available"}, 400)
        n = int(key.rsplit("_previous_day", 1)[1])
        hourly[key] = [10.0 * n] * len(t)
    return FakeResp({"hourly": hourly})


@pytest.fixture
def patched(monkeypatch, tmp_path):
    monkeypatch.setattr(weather.requests, "get", fake_open_meteo)
    return PreviousRunsProvider(cache_dir=tmp_path)


def test_target_window():
    idx, lead = target_window("2026-01-31")
    assert len(idx) == 48
    assert str(idx[0]) == "2026-02-01 00:00:00" and str(idx[-1]) == "2026-02-02 23:00:00"
    assert (lead[:24] == 1).all() and (lead[24:] == 2).all()


def test_strict_mode_uses_older_runs():
    assert lead_to_previous_day(1, "strict") == 2
    assert lead_to_previous_day(2, "strict") == 3
    assert lead_to_previous_day(1, "standard") == 1


def test_previous_runs_parsing_and_no_leakage(patched):
    nwp, meta = weather.fetch_issue_weather("2026-01-31", patched,
                                            models=["ecmwf_ifs025", "gfs_seamless"], mode="strict")
    assert meta["previous_days_used"] == {1: 2, 2: 3}
    # день D+1 должен быть взят из previous_day2 (=20), D+2 — из previous_day3 (=30)
    col = nwp["ecmwf_ifs025__wind_speed_10m"]
    assert (col[nwp.lead_day == 1] == 20).all() and (col[nwp.lead_day == 2] == 30).all()
    # у gfs нет 100 м → провайдер сам перешёл на поштучный запрос и не упал
    assert "gfs_seamless__wind_speed_10m" in nwp and "gfs_seamless__wind_speed_100m" not in nwp


def test_hash_changes_with_data(patched):
    nwp, meta = weather.fetch_issue_weather("2026-01-31", patched, models=["ecmwf_ifs025"])
    nwp2 = nwp.copy()
    nwp2.iloc[0, 1] += 1
    assert weather.weather_hash(nwp2) != meta["weather_hash"]


def test_analysis_flags_errors():
    idx = pd.date_range("2026-02-01", periods=48, freq="1h")
    fc = pd.DataFrame({"forecast": 0.5, "p10": 0.3, "p90": 0.7, "ens_ws_std": 1.0,
                       "ens_ws_mean": 8.0, "n_models": 3}, index=idx)
    assert analyze(fc)["status"] == "ok"
    fc.iloc[5, 0] = np.nan
    rep = analyze(fc)
    assert rep["status"] == "error" and rep["recommended_action"] == "rerun_with_fallback"


def test_end_to_end_mock_and_tools(tmp_path, monkeypatch):
    """Обучение на mock-погоде + вызовы через call_tool, как это сделает агент."""
    from windml import config, tools
    monkeypatch.setattr(config.CFG, "model_dir", tmp_path / "models")
    monkeypatch.setattr(config.CFG, "output_dir", tmp_path / "out")
    res = json.loads(tools.call_tool("train_model", {"start_date": "2025-06-01", "end_date": "2026-01-31",
                                                      "weather_source": "mock"}))
    assert res["status"] == "trained", res
    fc = json.loads(tools.call_tool("run_power_forecast", {"issue_date": "2026-01-10", "weather_source": "mock"}))
    assert len(fc["hourly"]) == 48
    assert all(0 <= h["p10"] <= h["p90"] <= 1 for h in fc["hourly"])
    upd = json.loads(tools.call_tool("check_weather_update", {"issue_date": "2026-01-10",
                                                              "previous_hash": fc["weather_hash"],
                                                              "weather_source": "mock"}))
    assert upd["updated"] is False
    ev = json.loads(tools.call_tool("evaluate_forecast", {"issue_date": "2026-01-10"}))
    assert ev["overall"]["n"] > 0
    err = json.loads(tools.call_tool("no_such_tool", {}))
    assert "error" in err
