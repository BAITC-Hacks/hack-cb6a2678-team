"""Reproducible dataset training with an untouched final-month test.

python -m windml.train_dataset --csv data/raw/turbine_1.csv --name turbine_1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import platform
import shutil
from datetime import datetime, timezone
from pathlib import Path

import lightgbm
import numpy as np
import pandas as pd
import sklearn

from .config import CFG, SITES
from .data import load_hourly
from .features import build_features
from .metrics import score
from .model import BASE_PARAMS, WindPowerModel
from .weather import PreviousRunsProvider, fetch_issue_weather, fetch_training_weather


def run(csv, name, weather_site="turbine_1", test_start="2026-01-01", threads=4):
    csv = Path(csv).resolve()
    if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789_" for c in name):
        raise ValueError("name must contain only lowercase letters, digits and underscores")
    BASE_PARAMS.update(n_jobs=threads, random_state=42, deterministic=True, force_col_wise=True)
    output = CFG.output_dir / "training" / name
    output.mkdir(parents=True, exist_ok=True)
    scada = load_hourly(path=csv)
    raw = pd.read_csv(csv)
    times = pd.to_datetime(raw.iloc[:, 1])
    end = str(scada.index.max().date())
    cutoff = pd.Timestamp(test_start)
    if cutoff <= pd.Timestamp(CFG.train_start) or cutoff > scada.index.max():
        raise ValueError("test_start must be within the available training period")
    provider = PreviousRunsProvider(weather_site)
    weather = fetch_training_weather(CFG.train_start, end, provider)
    features = build_features(weather, CFG.nwp_models)
    available = features.ens_ws_mean.notna()
    audit = {
        "source_csv": str(csv),
        "sha256": hashlib.sha256(csv.read_bytes()).hexdigest(),
        "raw_rows": len(raw),
        "duplicate_timestamps": int(times.duplicated().sum()),
        "raw_missing_values": {str(k): int(v) for k, v in raw.isna().sum().items()},
        "raw_period": [str(times.min()), str(times.max())],
        "hourly_rows": len(scada),
        "anomaly_hours": int(scada.anomaly.sum()),
        "weather_site": weather_site,
        "weather_site_assumption": ("Unconfirmed shared weather location: turbine_2 uses turbine_1 forecasts; confirm location before operational use."
                                    if name != weather_site else None),
        "weather_coordinates": [SITES[weather_site].lat, SITES[weather_site].lon],
        "weather_source": provider.name,
        "weather_rows_available": int(available.sum()),
        "weather_first_available": str(weather.index[available].min()),
        "versions": {"python": platform.python_version(), "pandas": pd.__version__,
                     "numpy": np.__version__, "lightgbm": lightgbm.__version__,
                     "scikit_learn": sklearn.__version__},
        "training_parameters": dict(BASE_PARAMS),
    }
    (output / "data_audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")

    logging.info("%s: fitting evaluation model using targets strictly before %s", name, cutoff)
    evaluation = WindPowerModel().fit(weather[weather.index < cutoff], scada[scada.index < cutoff])
    evaluation.meta.update(site=name, weather_source=provider.name, data=audit)
    test_weather = weather[weather.index >= cutoff]
    predictions = evaluation.predict(test_weather)
    predictions["actual"] = scada.power.reindex(predictions.index).to_numpy()
    predictions["anomaly"] = scada.anomaly.reindex(predictions.index).to_numpy()
    predictions["weather_available"] = available[weather.index >= cutoff].to_numpy()
    predictions.reset_index().to_csv(output / "test_predictions.csv", index=False)
    # Reset the repeated time index (one copy per lead) before metric alignment.
    measured = predictions[predictions.weather_available].reset_index(drop=True)
    y = measured.actual
    training_actuals = scada.loc[(scada.index < cutoff) &
                                scada.index.isin(weather.index[available]) & ~scada.anomaly, "power"]
    test = {
        "method": "untouched chronological test; no test labels used for fitting or early stopping",
        "period": [test_start, end],
        "evaluation_model_train_period": evaluation.meta["train_period"],
        "model": score(y, measured),
        "by_lead_day": {str(int(lead)): score(g.actual, g) for lead, g in measured.groupby("lead_day")},
        "baseline_power_curve": score(y, measured.pc_baseline),
        "baseline_climatology": score(y, pd.Series(training_actuals.mean(), index=y.index)),
        "includes_anomalies": True,
        "unique_hours_scored": int(predictions.index[predictions.actual.notna() & predictions.weather_available].nunique()),
    }
    evaluation.meta["test"] = test
    evaluation.save(output / "evaluation_model.joblib")
    evaluation.val_predictions_.to_csv(output / "evaluation_validation_predictions.csv", index=False)
    print(json.dumps({"site": name, "test": test}, ensure_ascii=False, indent=2), flush=True)

    logging.info("%s: fitting production model through %s", name, end)
    model = WindPowerModel().fit(weather, scada)
    model.meta.update(site=name, weather_source=provider.name, data=audit,
                      test_before_final_refit=test,
                      test_note="Test metrics belong to evaluation_model.joblib; production model is refit including the test month.")
    target = CFG.model_dir / ("wind_model.joblib" if name == "turbine_1" else f"wind_model_{name}.joblib")
    if target.exists():
        backup = output / "previous_models" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup.mkdir(parents=True, exist_ok=True)
        for existing in (target, target.with_suffix(".json")):
            if existing.exists():
                shutil.copy2(existing, backup / existing.name)
    model.save(target)
    model.val_predictions_.to_csv(output / "production_validation_predictions.csv", index=False)
    # Reload the saved artifact and verify a real 48-hour inference contract.
    sample_weather, sample_meta = fetch_issue_weather(end, provider)
    restored = WindPowerModel.load(target)
    sample = restored.predict(sample_weather)
    np.testing.assert_allclose(sample.forecast, model.predict(sample_weather).forecast)
    assert len(sample) == 48 and np.isfinite(sample[["forecast", "p10", "p50", "p90"]]).all().all()
    assert ((sample.p10 <= sample.p50) & (sample.p50 <= sample.p90)).all()
    assert sample[["forecast", "p10", "p50", "p90"]].ge(0).all().all()
    assert sample[["forecast", "p10", "p50", "p90"]].le(1).all().all()
    sample.to_csv(output / "forecast_next_48h.csv")
    summary = {"site": name, "model_file": str(target), "data": audit,
               "test": test, "production": model.meta,
               "smoke_forecast": sample_meta}
    (output / "report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    logging.info("Saved model %s and report %s", target, output / "report.json")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--weather-site", choices=list(SITES), default="turbine_1")
    parser.add_argument("--test-start", default="2026-01-01")
    parser.add_argument("--threads", default=4, type=int)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run(args.csv, args.name, args.weather_site, args.test_start, args.threads)


if __name__ == "__main__":
    main()
