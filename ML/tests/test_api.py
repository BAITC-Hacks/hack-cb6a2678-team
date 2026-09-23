"""HTTP contract tests, including inference with the saved real models/cache."""
from datetime import date

import pytest
from fastapi.testclient import TestClient

from windml import service
from windml.api import app
from windml.weather import WeatherUnavailable


@pytest.fixture
def client(monkeypatch):
    def no_network(*args, **kwargs):
        raise AssertionError("API tests must use cached weather")
    monkeypatch.setattr("windml.weather.requests.get", no_network)
    return TestClient(app)


def test_health_and_openapi(client):
    assert client.get("/health").json() == {"status": "ok"}
    schema = client.get("/openapi.json").json()
    assert "/v1/forecast" in schema["paths"]
    assert "/tools/{name}" in schema["paths"]
    assert client.post("/tools/unknown", json={}).status_code == 400


@pytest.mark.parametrize("body", [
    {}, {"issue_date": "invalid"},
    {"issue_date": "2026-01-31", "site": "../../other"},
    {"issue_date": "2026-01-31", "weather_source": "mock"},
    {"issue_date": "2026-01-31", "unexpected": True},
])
def test_bad_input_rejected(client, body):
    assert client.post("/v1/forecast", json=body).status_code == 422


@pytest.mark.parametrize("site", ["turbine_1", "turbine_2"])
def test_trained_model_forecast(client, site):
    if not service.model_path(site).exists():
        pytest.skip("Train real model before running artifact integration test")
    response = client.post("/v1/forecast", json={"site": site, "issue_date": "2026-01-31"})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["site"] == site
    assert data["target_start"] == "2026-02-01 00:00:00"
    assert data["target_end"] == "2026-02-02 23:00:00"
    assert data["weather_source"] == "open-meteo-previous-runs"
    assert len(data["hourly"]) == 48
    for row in data["hourly"]:
        assert 0 <= row["forecast"] <= 1
        assert 0 <= row["p10"] <= row["p50"] <= row["p90"] <= 1
    assert [r["lead_day"] for r in data["hourly"]] == [1] * 24 + [2] * 24
    info = client.get(f"/v1/models/{site}").json()
    assert info["site"] == site
    assert info["weather_source"] != "mock"
    assert info["test_before_final_refit"]["evaluation_model_train_period"][1] < "2026-01-01"
    if site == "turbine_2":
        assert data["weather_site_assumption"]


@pytest.mark.parametrize("error", [FileNotFoundError(), WeatherUnavailable("missing forecasts")])
def test_unavailable_returns_503(client, monkeypatch, error):
    def fail(request):
        raise error
    monkeypatch.setattr(service, "predict", fail)
    response = client.post("/v1/forecast", json={"issue_date": "2026-01-31"})
    assert response.status_code == 503
    assert "detail" in response.json()


def test_request_parses_date():
    assert service.ForecastRequest(issue_date="2026-01-31").issue_date == date(2026, 1, 31)
