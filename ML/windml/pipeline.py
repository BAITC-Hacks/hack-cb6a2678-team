"""Сквозные сценарии: обучение, выпуск одного прогноза, бэктест."""
from __future__ import annotations

import json
import logging
from pathlib import Path

import pandas as pd

from .analysis import analyze
from .config import CFG
from .data import load_hourly
from .metrics import score
from .model import WindPowerModel
from .weather import fetch_issue_weather, fetch_training_weather, get_provider

log = logging.getLogger(__name__)


def train(site="turbine_1", provider=None, start=None, end=None, models=None, save_path=None) -> WindPowerModel:
    scada = load_hourly(site)
    provider = provider or get_provider("previous_runs", site)
    nwp = fetch_training_weather(start or CFG.train_start, end or CFG.train_end, provider, models)
    model = WindPowerModel(nwp_models=models).fit(nwp, scada)
    model.meta["weather_source"] = provider.name
    model.save(save_path)
    return model


def forecast_issue(issue_date, model: WindPowerModel, provider=None, site="turbine_1",
                   out_dir: Path | None = None) -> tuple[pd.DataFrame, dict, dict]:
    """Полный цикл одного выпуска: погода → признаки → модель → анализ → файл."""
    provider = provider or get_provider("previous_runs", site)
    nwp, meta = fetch_issue_weather(issue_date, provider, models=model.nwp_models)
    fc = model.predict(nwp)
    fc.insert(0, "issue_date", meta["issue_date"])
    report = analyze(fc, meta, model.meta, expected_hours=len(nwp))
    out_dir = Path(out_dir or CFG.output_dir / "forecasts")
    out_dir.mkdir(parents=True, exist_ok=True)
    fp = out_dir / f"forecast_{meta['issue_date']}.csv"
    fc.round(4).to_csv(fp)
    meta["file"] = str(fp)
    (out_dir / f"forecast_{meta['issue_date']}.json").write_text(
        json.dumps({"meta": meta, "analysis": report}, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return fc, meta, report


def backtest(start, end, model: WindPowerModel, provider=None, site="turbine_1",
             out_dir: Path | None = None) -> dict:
    """Скользящее воспроизведение «как в прошлом»: выпуск каждый день D в [start, end]."""
    out_dir = Path(out_dir or CFG.output_dir / "backtest")
    frames, reports = [], {}
    for d in pd.date_range(start, end, freq="D"):
        try:
            fc, meta, rep = forecast_issue(d, model, provider, site, out_dir / "issues")
            frames.append(fc)
            reports[str(d.date())] = {"status": rep["status"], "action": rep["recommended_action"],
                                      "hash": meta["weather_hash"]}
        except Exception as e:  # noqa: BLE001
            reports[str(d.date())] = {"status": "error", "error": str(e)[:200]}
            log.error("issue %s failed: %s", d.date(), e)
    allfc = pd.concat(frames)
    allfc.round(4).to_csv(out_dir / "all_issues.csv")
    # «итоговый» ряд: для каждого часа — самый свежий выпуск (lead_day=1)
    final = (allfc.reset_index().sort_values(["time_local", "lead_day"])
             .drop_duplicates("time_local").set_index("time_local"))
    final.round(4).to_csv(out_dir / "final_hourly_forecast.csv")
    result = {"issues": reports, "files": {"all_issues": str(out_dir / "all_issues.csv"),
                                            "final": str(out_dir / "final_hourly_forecast.csv")}}
    try:
        y = load_hourly(site)["power"]
        result["metrics_final"] = score(y.reindex(final.index), final)
        result["metrics_by_lead_day"] = {
            int(l): score(y.reindex(g.index), g) for l, g in allfc.groupby("lead_day")}
    except Exception as e:  # noqa: BLE001 — факта за тестовый период может не быть
        result["metrics_note"] = f"no actuals: {e}"
    (out_dir / "backtest_summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return result
