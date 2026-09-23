"""Проверка именно командного контракта, а не сгенерированной FastAPI схемы."""
from pathlib import Path

import jsonschema
import pytest
import yaml
from fastapi.testclient import TestClient

from windml.api import app


def json_schema(node):
    if isinstance(node, list):
        return [json_schema(v) for v in node]
    if not isinstance(node, dict):
        return node
    result = {k: json_schema(v) for k, v in node.items() if k != "nullable"}
    if node.get("nullable"):
        return {"anyOf": [result, {"type": "null"}]}
    return result


CONTRACT = json_schema(yaml.safe_load((Path(__file__).resolve().parents[2] /
                                     "contracts/ml-service.openapi.yaml").read_text(encoding="utf-8")))


@pytest.mark.parametrize("site", ["turbine_1", "turbine_2"])
@pytest.mark.parametrize("route,method", [("/v1/forecast", "post"), ("/v1/models/{site}", "get"),
                                         ("/v1/evaluation/{site}", "get")])
def test_responses_match_external_contract(site, route, method, monkeypatch):
    monkeypatch.setenv("WINDML_OFFLINE", "1")
    client = TestClient(app)
    endpoint = route.format(site=site)
    response = (client.post(endpoint, json={"site": site, "issue_date": "2026-01-31"})
                if method == "post" else client.get(endpoint))
    assert response.status_code == 200, response.text
    schema = CONTRACT["paths"][route][method]["responses"]["200"]["content"]["application/json"]["schema"]
    validator = jsonschema.Draft7Validator({**schema, "components": CONTRACT["components"]},
                                          format_checker=jsonschema.FormatChecker())
    data = response.json()
    validator.validate(data)
    rows = data.get("hourly", data.get("rows", []))
    for row in rows:
        assert 0 <= row["p10"] <= row["p50"] <= row["p90"] <= 1
        assert row["p10"] <= row["forecast"] <= row["p90"]
        assert row["time_utc"].endswith("Z")
    if "hourly" in data:
        assert len(rows) == 48
    if route == "/v1/models/{site}":
        assert {"model_version", "trained_until_utc", "local_tz", "weather_models"} <= data.keys()
