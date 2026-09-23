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
import sys
from datetime import datetime, timezone
from pathlib import Path

import lightgbm
import numpy as np
import pandas as pd
import sklearn

from .config import CFG, SITES, LOCAL_TZ, SCADA_TZ, WEATHER_CACHE_SITES
from .calibration import apply_calibration, fit_bias, fit_intervals, fit_warning_thresholds
from .timeutils import utc_string
from .data import load_hourly
from .features import build_features
from .metrics import score
from .model import BASE_PARAMS, WindPowerModel
from .weather import PreviousRunsProvider, fetch_issue_weather, fetch_training_weather


def run(csv, name, weather_site=None, test_start="2026-01-01", threads=4):
    csv = Path(csv).resolve()
    if not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789_" for c in name):
        raise ValueError("name must contain only lowercase letters, digits and underscores")
    BASE_PARAMS.update(n_jobs=threads, random_state=42, deterministic=True, force_col_wise=True)
    weather_site = weather_site or name
    if test_start != "2026-01-01":
        raise ValueError("The frozen ML-task evaluation starts on 2026-01-01")
    output = CFG.output_dir / "training" / name
    output.mkdir(parents=True, exist_ok=True)
    scada = load_hourly(path=csv, scada_tz=SCADA_TZ).loc[:"2026-01-31 23:00:00"]
    raw = pd.read_csv(csv)
    times = pd.to_datetime(raw.iloc[:, 1])
    end = "2026-01-31"
    cutoff = pd.Timestamp(test_start)
    if cutoff <= pd.Timestamp(CFG.train_start) or cutoff > scada.index.max():
        raise ValueError("test_start must be within the available training period")
    provider = PreviousRunsProvider(weather_site)
    weather = fetch_training_weather(CFG.train_start, end, provider)
    features = build_features(weather, CFG.nwp_models)
    available = features.ens_ws_mean.notna()
    audit = {
        "source_csv": f"data/raw/{name}.csv",
        "sha256": hashlib.sha256(csv.read_bytes()).hexdigest(),
        "raw_rows": len(raw),
        "duplicate_timestamps": int(times.duplicated().sum()),
        "raw_missing_values": {str(k): int(v) for k, v in raw.isna().sum().items()},
        "raw_period": [str(times.min()), str(times.max())],
        "hourly_rows": len(scada),
        "anomaly_hours": int(scada.anomaly.sum()),
        "weather_site": weather_site,
        "weather_site_assumption": None,
        "weather_cache_site": WEATHER_CACHE_SITES.get(weather_site, weather_site),
        "scada_tz": SCADA_TZ, "local_tz": LOCAL_TZ,
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

    # Все решения заморожены на независимом прогнозе ноября–декабря от модели до ноября.
    selection = CFG.output_dir / "selection"
    comparison = json.loads((selection / "timezone_comparison.json").read_text())
    if comparison["chosen_scada_tz"] != SCADA_TZ:
        raise ValueError("SCADA_TZ must match pre-January timezone selection")
    prefix = selection / f"{name}_{SCADA_TZ.replace('/', '_')}"
    reference = WindPowerModel.load(prefix.with_suffix(".joblib"))
    validation = pd.read_csv(str(prefix) + "_validation.csv", index_col=0, parse_dates=True)
    calibration = fit_intervals(validation, fit_bias(validation))
    calibrated_validation = apply_calibration(validation, calibration)
    warnings = fit_warning_thresholds(calibrated_validation)
    iterations = {key: int(value * 1.1) for key, value in reference.meta["best_iterations"].items()}
    tuning = {"scada_tz": SCADA_TZ, "local_tz": LOCAL_TZ,
              "calibration": calibration, "warning_calibration": warnings,
              "selection_model_train_period": reference.meta["train_period"]}
    (output / "frozen_settings.json").write_text(json.dumps({**tuning, "iterations": iterations}, indent=2), encoding="utf-8")

    logging.info("%s: fitting evaluation model using targets strictly before %s", name, cutoff)
    evaluation = WindPowerModel().fit_frozen(weather[weather.index < cutoff], scada[scada.index < cutoff], iterations)
    evaluation.meta.update(site=name, weather_source=provider.name, data=audit, **tuning)
    test_weather = weather[weather.index >= cutoff]
    predictions = evaluation.predict(test_weather)
    predictions["time_utc"] = [utc_string(t) for t in predictions.index]
    predictions["issue_date"] = [(t.normalize() - pd.Timedelta(days=int(lead))).date().isoformat()
                                 for t, lead in zip(predictions.index, predictions.lead_day)]
    predictions["actual"] = scada.power.reindex(predictions.index).to_numpy()
    predictions["anomaly"] = scada.anomaly.reindex(predictions.index).fillna(False).astype(bool).to_numpy()
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
        "climatology_value": float(training_actuals.mean()),
        "baselines_by_lead_day": {str(int(lead)): {
            "power_curve": score(g.actual, g.pc_baseline),
            "climatology": score(g.actual, pd.Series(training_actuals.mean(), index=g.index))}
            for lead, g in measured.groupby("lead_day")},
        "bias_diagnosis": {"anomaly_share": float(measured.anomaly.mean()),
                           "normal_hours": score(measured.loc[~measured.anomaly, "actual"], measured.loc[~measured.anomaly]),
                           "anomaly_hours": score(measured.loc[measured.anomaly, "actual"], measured.loc[measured.anomaly])},
        "includes_anomalies": True,
        "unique_hours_scored": int(predictions.index[predictions.actual.notna() & predictions.weather_available].nunique()),
    }
    evaluation.meta["test"] = test
    evaluation.save(output / "evaluation_model.joblib")
    calibrated_validation.to_csv(output / "evaluation_validation_predictions.csv")
    print(json.dumps({"site": name, "test": test}, ensure_ascii=False, indent=2), flush=True)

    logging.info("%s: fitting production model through %s", name, end)
    model = WindPowerModel().fit_frozen(weather, scada, iterations)
    model.meta.update(site=name, weather_source=provider.name, data=audit,
                      **tuning,
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
    calibrated_validation.to_csv(output / "production_validation_predictions.csv")
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
    summary = {"site": name, "model_file": f"models/{target.name}", "data": audit,
               "test": test, "production": model.meta,
               "smoke_forecast": sample_meta}
    (output / "report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    logging.info("Saved model %s and report %s", target, output / "report.json")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--weather-site", choices=list(SITES))
    parser.add_argument("--test-start", default="2026-01-01")
    parser.add_argument("--threads", default=4, type=int)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    run(args.csv, args.name, args.weather_site, args.test_start, args.threads)


if __name__ == "__main__":
    main()
