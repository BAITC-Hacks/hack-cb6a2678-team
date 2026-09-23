"""CLI: python -m windml <команда> ...

  train     [--start --end --source]      обучить модель
  forecast  --date YYYY-MM-DD [--source]  один выпуск (48 ч) + анализ
  backtest  --start --end [--source]      скользящий выпуск по дням
  info                                     метрики и параметры модели
  schemas                                  JSON-схемы инструментов для агента
"""
from __future__ import annotations

import argparse
import json
import logging

from . import tools


def main(argv=None):
    ap = argparse.ArgumentParser(prog="windml")
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train"); t.add_argument("--start"); t.add_argument("--end")
    f = sub.add_parser("forecast"); f.add_argument("--date", required=True)
    b = sub.add_parser("backtest"); b.add_argument("--start", required=True); b.add_argument("--end", required=True)
    sub.add_parser("info"); sub.add_parser("schemas")
    for p in (t, f, b):
        p.add_argument("--source", default=tools.DEFAULT_SOURCE, choices=["previous_runs", "live", "mock"])
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    if a.cmd == "train":
        res = tools.train_model(a.start, a.end, a.source)
    elif a.cmd == "forecast":
        res = tools.run_power_forecast(a.date, a.source, include_hourly=False)
    elif a.cmd == "backtest":
        res = tools.run_backtest(a.start, a.end, a.source)
    elif a.cmd == "info":
        res = tools.get_model_info()
    else:
        res = tools.openai_schemas()
    print(json.dumps(res, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
