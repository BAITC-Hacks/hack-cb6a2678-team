"""Только оценка замороженных моделей; результаты не возвращаются в подбор."""
import json
from collections import Counter

import pandas as pd

from .config import CFG
from .data import load_hourly
from .features import build_features
from .metrics import score
from .service import ForecastRequest, predict, get_model, evaluation
from .weather import fetch_training_weather


def run():
    output = CFG.output_dir / "task_report"
    output.mkdir(parents=True, exist_ok=True)
    weather = fetch_training_weather(CFG.train_start, "2025-12-31")
    available = build_features(weather, CFG.nwp_models).ens_ws_mean.notna()
    result = {"metrics": [], "february": {}, "bias_diagnosis": {}}
    markdown = ["| Турбина | D+ | Этап | nMAE % | nRMSE % | Bias п.п. | Покрытие % | Кривая nMAE / nRMSE % | Средняя nMAE / nRMSE % |",
                "|---|---|---|---:|---:|---:|---:|---:|---:|"]
    for site in ("turbine_1", "turbine_2"):
        old_scada = load_hourly(site, scada_tz="Asia/Almaty")
        constant_before = float(old_scada.loc[(old_scada.index < "2026-01-01") &
                                old_scada.index.isin(weather.index[available]) & ~old_scada.anomaly, "power"].mean())
        report = json.loads((CFG.output_dir / "training" / site / "report.json").read_text(encoding="utf-8"))
        result["bias_diagnosis"][site] = report["test"]["bias_diagnosis"]
        for stage, source, constant in (("до", "before_tasks", constant_before),
                                         ("после", "training", report["test"]["climatology_value"])):
            frame = pd.read_csv(CFG.output_dir / source / site / "test_predictions.csv")
            for lead, group in frame.groupby("lead_day"):
                model = score(group.actual, group)
                pc = score(group.actual, group.pc_baseline)
                climate = score(group.actual, pd.Series(constant, index=group.index))
                result["metrics"].append({"site": site, "lead_day": int(lead), "stage": stage,
                                          "model": model, "power_curve": pc, "climatology": climate})
                markdown.append(f"| {site} | {lead} | {stage} | {model['nMAE_%']} | {model['nRMSE_%']} | {model['bias_%']} | {model['coverage_10_90_%']} | {pc['nMAE_%']} / {pc['nRMSE_%']} | {climate['nMAE_%']} / {climate['nRMSE_%']} |")
        statuses, codes, forecasts = Counter(), Counter(), []
        for day in pd.date_range("2026-01-31", "2026-02-26"):
            forecast = predict(ForecastRequest(site=site, issue_date=day.date())).model_dump()
            forecasts.append(forecast)
            statuses[forecast["analysis"]["status"]] += 1
            codes.update({issue["code"] for issue in forecast["analysis"]["issues"]})
        (output / f"{site}_february.json").write_text(json.dumps(forecasts, ensure_ascii=False, indent=2), encoding="utf-8")
        result["february"][site] = {"issues": 27, "status_counts": dict(statuses),
                                     "warning_rate": statuses["warning"] / 27,
                                     "code_counts": dict(codes), "thresholds": get_model(site).meta["warning_calibration"]["thresholds"]}
        (output / f"{site}_evaluation.json").write_text(evaluation(site).model_dump_json(indent=2), encoding="utf-8")
    (output / "metrics.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(markdown))
    print(json.dumps(result["february"], indent=2))


if __name__ == "__main__":
    run()
