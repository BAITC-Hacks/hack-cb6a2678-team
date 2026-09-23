#!/usr/bin/env python3
"""Backtest: цикл агента для каждой даты выпуска и каждой турбины.

Запускает POST /api/forecast/run, ждёт завершения по GET /api/agent-log и печатает итог.
Результаты циклов бэкенд сохраняет в data/cycles/<турбина>/<дата>.json.

Нужны запущенные ML-сервис, бэкенд и (для отчётов LLM) Ollama. Только стандартная библиотека Python.

    python3 scripts/backtest.py                      # все даты из /api/meta, обе турбины
    python3 scripts/backtest.py 2026-02-10 2026-02-12 # диапазон дат
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

API = "http://localhost:8080/api"
TIMEOUT_S = 300


def get(path):
    with urllib.request.urlopen(API + path, timeout=60) as r:
        return json.load(r)


def post(path, body):
    req = urllib.request.Request(API + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def run_cycle(turbine, day):
    post("/forecast/run", {"turbineId": turbine, "date": day})
    started = time.time()
    while time.time() - started < TIMEOUT_S:
        time.sleep(1)
        try:
            log = get(f"/agent-log?date={day}&turbineId={turbine}")
        except urllib.error.HTTPError as e:
            if e.code == 404:  # цикл ещё не успел записать первый шаг
                continue
            raise
        if log["status"] != "running":
            return log, time.time() - started
    raise TimeoutError(f"{turbine} {day}: цикл не завершился за {TIMEOUT_S} с")


def main():
    meta = get("/meta")
    first = date.fromisoformat(sys.argv[1] if len(sys.argv) > 1 else meta["backtestFrom"])
    last = date.fromisoformat(sys.argv[2] if len(sys.argv) > 2 else (sys.argv[1] if len(sys.argv) > 1 else meta["backtestTo"]))
    turbines = [t["id"] for t in get("/turbines")]
    print(f"Backtest {first}…{last}, турбины {', '.join(turbines)}, модель {meta['modelVersion']}")

    failed, reports = [], {"llm": 0, "template": 0}
    day = first
    while day <= last:
        for t in turbines:
            log, took = run_cycle(t, day.isoformat())
            steps = " ".join("✓" if s["status"] == "success" else "↻" if s["status"] == "retrying" else "✗" for s in log["steps"])
            print(f"  {day} {t}: {log['status']:7} {steps}  отчёт={log['reportSource']}  {took:4.0f} с", flush=True)
            if log["status"] != "success":
                failed.append(f"{t} {day}")
            elif log["reportSource"] in reports:
                reports[log["reportSource"]] += 1
        day += timedelta(days=1)

    print(f"Готово: успешно {sum(reports.values())}, с ошибкой {len(failed)} {failed}; "
          f"отчёты LLM {reports['llm']}, по шаблону {reports['template']}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
