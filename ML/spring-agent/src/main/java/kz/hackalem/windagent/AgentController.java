package kz.hackalem.windagent;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private final ForecastAgent agent;

    public AgentController(ForecastAgent agent) {
        this.agent = agent;
    }

    /** Один выпуск: GET /agent/forecast?date=2026-01-31 */
    @GetMapping("/forecast")
    public Map<String, String> forecast(@RequestParam String date) {
        return Map.of("issue_date", date, "report", agent.forecast(date));
    }

    /**
     * Воспроизведение тестового периода «как в прошлом»:
     * GET /agent/replay?start=2026-01-31&end=2026-02-27 — агент выпускает прогноз на каждый день.
     */
    @GetMapping("/replay")
    public Map<String, String> replay(@RequestParam String start, @RequestParam String end) {
        Map<String, String> reports = new LinkedHashMap<>();
        for (LocalDate d = LocalDate.parse(start); !d.isAfter(LocalDate.parse(end)); d = d.plusDays(1)) {
            reports.put(d.toString(), agent.forecast(d.toString()));
        }
        return reports;
    }

    /** Свободный вопрос агенту: POST /agent/ask  {"question": "..."} */
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) {
        return Map.of("answer", agent.ask(body.get("question")));
    }
}
