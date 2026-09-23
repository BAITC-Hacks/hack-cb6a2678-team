"""Выбор часового пояса только на ноябре–декабре 2025; январь не читается при подборе."""
from __future__ import annotations

import json
import logging
import sys

import numpy as np
import pandas as pd

from .config import CFG, LOCAL_TZ
from .data import load_hourly
from .features import build_features
from .metrics import score
from .model import WindPowerModel, BASE_PARAMS
from .weather import fetch_training_weather

ZONES = ("Asia/Almaty", "Etc/GMT-6", "Etc/GMT-5")
VALIDATION_START = pd.Timestamp("2025-11-01")
VALIDATION_END = pd.Timestamp("2025-12-31 23:00:00")


def compare_timezones():
    BASE_PARAMS.update(n_jobs=4, random_state=42, deterministic=True, force_col_wise=True)
    output = CFG.output_dir / "selection"
    output.mkdir(parents=True, exist_ok=True)
    weather = fetch_training_weather(CFG.train_start, "2025-12-31")
    features = build_features(weather, CFG.nwp_models)
    results = []
    for site in ("turbine_1", "turbine_2"):
        for zone in ZONES:
            key = zone.replace("/", "_")
            target = output / f"{site}_{key}.json"
            if target.exists():
                results.append(json.loads(target.read_text()))
                continue
            scada = load_hourly(path=CFG.cache_dir.parent / "raw" / f"{site}.csv", scada_tz=zone)
            scada = scada.loc[:VALIDATION_END]
            model = WindPowerModel().fit(weather[weather.index < VALIDATION_START], scada[scada.index < VALIDATION_START])
            validation = weather[(weather.index >= VALIDATION_START) & (weather.index <= VALIDATION_END)]
            pred = model.predict(validation)
            pred["actual"] = scada.power.reindex(pred.index).to_numpy()
            pred["anomaly"] = scada.anomaly.reindex(pred.index).fillna(False).to_numpy()
            pred = pred.loc[pred.actual.notna() & (pred.n_models > 0)]
            measured = pred.reset_index(drop=True)
            # Независимая проверка фазового сдвига по прогнозному ветру из кэша.
            one = features[weather.lead_day == 1]
            corr = {}
            for label, lo, hi in (("before_transition", "2024-01-20", "2024-02-29 22:00"),
                                  ("after_transition", "2024-03-01", "2025-12-31 23:00")):
                forecast_wind = one.ens_ws_mean.loc[lo:hi]
                observed_wind = scada.ws_obs.reindex(forecast_wind.index)
                corr[label] = float(forecast_wind.corr(observed_wind))
            result = {"site": site, "scada_tz": zone, "local_tz": LOCAL_TZ,
                      "fit_period": model.meta["train_period"], "validation_period": [str(VALIDATION_START), str(VALIDATION_END)],
                      "metrics": score(measured.actual, measured), "wind_correlation": corr,
                      "by_lead_day": {str(int(k)): score(g.actual, g) for k, g in measured.groupby("lead_day")}}
            model.meta.update(site=site, scada_tz=zone, local_tz=LOCAL_TZ)
            model.save(output / f"{site}_{key}.joblib")
            pred.to_csv(output / f"{site}_{key}_validation.csv")
            target.write_text(json.dumps(result, indent=2), encoding="utf-8")
            results.append(result)
            print(json.dumps(result), flush=True)
    aggregate = {zone: float(np.mean([r["metrics"]["nMAE_%"] for r in results if r["scada_tz"] == zone])) for zone in ZONES}
    chosen = min(aggregate, key=aggregate.get)
    report = {"chosen_scada_tz": chosen, "criterion": "mean November-December validation nMAE over both turbines",
              "scores": aggregate, "candidates": results}
    (output / "timezone_comparison.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"chosen": chosen, "scores": aggregate}), flush=True)
    return report


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, stream=sys.stdout)
    compare_timezones()
