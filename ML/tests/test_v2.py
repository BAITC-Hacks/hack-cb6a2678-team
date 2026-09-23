import pandas as pd
import pytest
from fastapi.testclient import TestClient

from windml.api import app


@pytest.fixture
def client(monkeypatch):
    def no_network(*args, **kwargs):
        raise AssertionError("Offline artifacts must be sufficient")
    monkeypatch.setattr("windml.weather.requests.get", no_network)
    return TestClient(app)


def test_utc_window(client):
    response = client.post("/v1/forecast", json={"site": "turbine_1", "issue_date": "2026-01-31"})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["issued_at_utc"] == "2026-01-31T07:00:00Z"
    assert data["local_tz"] == "Asia/Almaty"
    assert data["target_start_utc"] == "2026-01-31T19:00:00Z"
    assert data["target_end_utc"] == "2026-02-02T18:00:00Z"
    times = pd.to_datetime([r["time_utc"] for r in data["hourly"]], utc=True)
    assert times.equals(pd.date_range(data["target_start_utc"], periods=48, freq="h"))


@pytest.mark.parametrize("site", ["turbine_1", "turbine_2"])
def test_every_february_issue_is_leakage_safe(client, site):
    info = client.get(f"/v1/models/{site}").json()
    for day in pd.date_range("2026-01-31", "2026-02-26"):
        response = client.post("/v1/forecast", json={"site": site, "issue_date": str(day.date())})
        assert response.status_code == 200, response.text
        data = response.json()
        assert pd.Timestamp(data["weather_issued_before_utc"]) <= pd.Timestamp(data["issued_at_utc"])
        assert data["model_version"] == info["model_version"]
        assert data["weather_models"] == info["weather_models"]
        assert data["leakage_mode"] == "strict"
    assert pd.Timestamp(info["trained_until_utc"]) < pd.Timestamp("2026-02-01T00:00Z")


def test_unsafe_weather_is_rejected(client, monkeypatch):
    from windml import service
    original = service.fetch_issue_weather
    def unsafe(*args, **kwargs):
        frame, meta = original(*args, **kwargs)
        meta["weather_issued_before_utc"] = "2026-02-01T00:00:00Z"
        return frame, meta
    monkeypatch.setattr(service, "fetch_issue_weather", unsafe)
    assert client.post("/v1/forecast", json={"issue_date": "2026-01-31"}).status_code == 503


def test_standard_mode_bound_is_rejected_before_fetch():
    from windml.weather import fetch_issue_weather, WeatherUnavailable
    with pytest.raises(WeatherUnavailable, match="issuance bound"):
        fetch_issue_weather("2026-01-31", mode="standard")


def test_hourly_weather_matches_ensemble(client):
    from windml.weather import fetch_issue_weather
    from windml.features import build_features
    from windml.config import CFG
    data = client.post("/v1/forecast", json={"issue_date": "2026-01-31"}).json()
    weather, _ = fetch_issue_weather("2026-01-31")
    features = build_features(weather, CFG.nwp_models)
    for row, (_, expected) in zip(data["hourly"], features.iterrows()):
        assert row["wind_speed_100m"] == pytest.approx(expected.ens_ws_mean, abs=1e-6)
        assert row["wind_spread"] == pytest.approx(expected.ens_ws_std, abs=1e-6)
        assert row["temperature_2m"] == pytest.approx(expected.temp, abs=1e-6)


def test_issue_intervals_split_at_gaps():
    from windml.analysis import analyze
    index = pd.date_range("2026-02-01", periods=48, freq="h")
    frame = pd.DataFrame({"forecast": .5, "p10": .4, "p90": .6, "ens_ws_std": 1.}, index=index)
    frame.loc[index[[0, 1, 4, 47]], "ens_ws_std"] = 5.
    issues = [x for x in analyze(frame)["issues"] if x["code"] == "HIGH_NWP_DISAGREEMENT"]
    assert len(issues) == 3
    assert [(x["from_utc"], x["to_utc"]) for x in issues] == [
        ("2026-01-31T19:00:00Z", "2026-01-31T20:00:00Z"),
        ("2026-01-31T23:00:00Z", "2026-01-31T23:00:00Z"),
        ("2026-02-02T18:00:00Z", "2026-02-02T18:00:00Z")]


@pytest.mark.parametrize("site", ["turbine_1", "turbine_2"])
def test_evaluation_returns_test_model_and_actuals(client, site):
    from windml.config import CFG
    response = client.get(f"/v1/evaluation/{site}")
    assert response.status_code == 200, response.text
    data = response.json()
    source = pd.read_csv(CFG.output_dir / "training" / site / "test_predictions.csv")
    assert len(data["rows"]) == len(source)
    assert data["model_version"] != client.get(f"/v1/models/{site}").json()["model_version"]
    assert pd.Timestamp(data["trained_until_utc"]) < pd.Timestamp(data["period_start_utc"])
    assert data["rows"][0]["actual"] == pytest.approx(source.iloc[0].actual, abs=1e-6)
    assert {r["lead_day"] for r in data["rows"]} == {1, 2}
    assert all(type(r["anomaly"]) is bool for r in data["rows"])


def test_missing_evaluation_returns_503(client, monkeypatch, tmp_path):
    from windml.config import CFG
    monkeypatch.setattr(CFG, "output_dir", tmp_path)
    assert client.get("/v1/evaluation/turbine_1").status_code == 503
    assert client.get("/v1/evaluation/unknown").status_code == 422


def test_historical_live_rejected_before_network(client, monkeypatch):
    from windml.weather import LiveForecastProvider
    def fail(*args, **kwargs):
        pytest.fail("Historical live request must be rejected before fetching")
    monkeypatch.setattr(LiveForecastProvider, "fetch", fail)
    response = client.post("/v1/forecast", json={"site": "turbine_1", "issue_date": "2026-01-31", "weather_source": "live"})
    assert response.status_code == 503


def test_evaluation_missing_actual_is_null(client, monkeypatch, tmp_path):
    import shutil
    from windml.config import CFG
    source = CFG.output_dir / "training/turbine_1"
    target = tmp_path / "training/turbine_1"
    target.mkdir(parents=True)
    shutil.copy2(source / "evaluation_model.joblib", target / "evaluation_model.joblib")
    frame = pd.read_csv(source / "test_predictions.csv")
    frame.loc[0, "actual"] = float("nan")
    frame.to_csv(target / "test_predictions.csv", index=False)
    monkeypatch.setattr(CFG, "output_dir", tmp_path)
    response = client.get("/v1/evaluation/turbine_1")
    assert response.status_code == 200
    assert response.json()["rows"][0]["actual"] is None
