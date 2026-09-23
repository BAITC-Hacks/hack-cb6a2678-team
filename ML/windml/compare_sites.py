"""Проверка координат обеих турбин по реальным ответам Previous Runs."""
import json
from datetime import datetime, timezone

import numpy as np

from .config import CFG, SITES
from .weather import PREV_RUNS_URL, _get_json


def run():
    CFG.http_retries = 1
    output = CFG.output_dir / "selection"
    output.mkdir(parents=True, exist_ok=True)
    results = {}
    for model in CFG.nwp_models:
        path = output / f"site_comparison_{model}.json"
        if path.exists():
            response = json.loads(path.read_text())
        else:
            params = dict(latitude=",".join(str(s.lat) for s in SITES.values()),
                          longitude=",".join(str(s.lon) for s in SITES.values()), models=model,
                          hourly=",".join(f"{v}_previous_day{n}" for v in CFG.hourly_vars for n in (2, 3)),
                          start_date="2025-12-01", end_date="2025-12-07", wind_speed_unit="ms", timezone="GMT")
            response = _get_json(PREV_RUNS_URL, params)
            path.write_text(json.dumps(response), encoding="utf-8")
        first, second = response
        differences = {}
        for key, a in first["hourly"].items():
            if key == "time":
                assert a == second["hourly"][key]
                continue
            a, b = np.asarray(a, dtype=float), np.asarray(second["hourly"][key], dtype=float)
            same = np.allclose(a, b, rtol=0, atol=0, equal_nan=True)
            if not same:
                differences[key] = float(np.nanmax(np.abs(a - b)))
        results[model] = {"identical": not differences, "max_differences": differences,
                          "locations": [{k: value[k] for k in ("latitude", "longitude", "elevation")} for value in response]}
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "period": ["2025-12-01", "2025-12-07"],
              "requested_coordinates": {name: [s.lat, s.lon] for name, s in SITES.items()},
              "models": results, "all_identical": all(r["identical"] for r in results.values())}
    (output / "weather_site_comparison.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    run()
