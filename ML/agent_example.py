"""Пример интеграции ML-инструмента в агента (для напарника).

1) reference_cycle() — детерминированный оркестратор без LLM: показывает, какой
   цикл ожидается от агента (погода → прогноз → анализ → пересчёт при обновлении).
2) claude_agent()    — тот же цикл, но решения принимает LLM через tool use.
   pip install anthropic; export ANTHROPIC_API_KEY=...
"""
import json
import sys

import pandas as pd

from windml.tools import TOOL_SCHEMAS, call_tool


def reference_cycle(start="2026-01-31", end="2026-02-27", source="previous_runs"):
    for d in pd.date_range(start, end, freq="D"):
        day = str(d.date())
        res = json.loads(call_tool("run_power_forecast",
                                   {"issue_date": day, "weather_source": source, "include_hourly": False}))
        if "error" in res:
            print(day, "ERROR", res["error"]); continue
        a = res["analysis"]
        print(day, a["status"], a["recommended_action"], a["stats"]["capacity_factor_by_day"])
        if a["recommended_action"] == "accept_and_recheck_on_update":
            upd = json.loads(call_tool("check_weather_update", {"issue_date": day,
                                                                "previous_hash": res["weather_hash"],
                                                                "weather_source": source}))
            if upd.get("updated"):
                call_tool("run_power_forecast", {"issue_date": day, "weather_source": source})
                print("   ↳ погода обновилась — прогноз пересчитан")


def claude_agent(issue_date="2026-01-31", model="claude-sonnet-4-5"):
    import anthropic
    client = anthropic.Anthropic()
    messages = [{"role": "user", "content":
                 f"Сделай прогноз выработки ВЭС, выпуск {issue_date}. Проанализируй результат, "
                 f"при необходимости проверь обновление погоды и пересчитай. Дай краткий отчёт."}]
    while True:
        r = client.messages.create(model=model, max_tokens=2000, tools=TOOL_SCHEMAS, messages=messages)
        messages.append({"role": "assistant", "content": r.content})
        if r.stop_reason != "tool_use":
            print("".join(b.text for b in r.content if b.type == "text"))
            return
        messages.append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": b.id, "content": call_tool(b.name, b.input)}
            for b in r.content if b.type == "tool_use"]})


if __name__ == "__main__":
    claude_agent() if "--llm" in sys.argv else reference_cycle()
