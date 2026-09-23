"""HTTP smoke test from inside a container started with --network none."""
import json
import time
import urllib.request

base = "http://127.0.0.1:8000"
for attempt in range(30):
    try:
        with urllib.request.urlopen(base + "/health", timeout=2) as response:
            assert json.load(response) == {"status": "ok"}
        break
    except OSError:
        if attempt == 29:
            raise
        time.sleep(1)
for site in ("turbine_1", "turbine_2"):
    body = json.dumps({"site": site, "issue_date": "2026-01-31"}).encode()
    request = urllib.request.Request(base + "/v1/forecast", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        forecast = json.load(response)
    assert len(forecast["hourly"]) == 48
    assert forecast["issued_at_utc"] == "2026-01-31T07:00:00Z"
    assert forecast["weather_issued_before_utc"] <= forecast["issued_at_utc"]
    for row in forecast["hourly"]:
        assert 0 <= row["p10"] <= row["p50"] <= row["p90"] <= 1
        assert row["p10"] <= row["forecast"] <= row["p90"]
    with urllib.request.urlopen(base + f"/v1/evaluation/{site}", timeout=30) as response:
        evaluation = json.load(response)
    assert len(evaluation["rows"]) == 1488
    assert evaluation["trained_until_utc"] < evaluation["period_start_utc"]
    print(json.dumps({"site": site, "hours": 48, "evaluation_rows": len(evaluation["rows"]),
                      "version": forecast["model_version"], "status": "passed"}))
