package kz.hackalem.windagent;

import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Инструменты агента. Каждый метод с @Tool Spring AI автоматически описывает
 * для LLM (имя, описание, JSON-схема параметров), а когда LLM решает его вызвать —
 * исполняет метод и возвращает результат модели.
 *
 * Сами вычисления делает Python-сервис windml (ML-модель), здесь только HTTP-мост.
 */
@Component
public class WindForecastTools {

    private final RestClient http;
    private final String weatherSource;

    public WindForecastTools(@Value("${windml.base-url}") String baseUrl,
                             @Value("${windml.weather-source}") String weatherSource) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.weatherSource = weatherSource;
    }

    private String call(String tool, Map<String, Object> args) {
        return http.post().uri("/tools/{name}", tool)
                .body(args)
                .retrieve()
                // ошибки инструмента не роняем: возвращаем JSON {"error": ...} модели,
                // чтобы агент сам решил, что делать дальше
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .body(String.class);
    }

    private Map<String, Object> args(String issueDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("issue_date", issueDate);
        m.put("weather_source", weatherSource);
        return m;
    }

    @Tool(description = """
            Скачивает из Open-Meteo прогнозы погоды нескольких моделей (ECMWF, GFS, ICON),
            доступные на момент выпуска, для координат ВЭС. Возвращает список доступных моделей,
            средний ветер по суткам и weather_hash (отпечаток входных данных).""")
    public String getWeatherForecast(
            @ToolParam(description = "Дата выпуска D, YYYY-MM-DD. Прогноз на сутки D+1 и D+2") String issueDate) {
        return call("get_weather_forecast", args(issueDate));
    }

    @Tool(description = """
            Полный цикл: погода → признаки → ML-модель → почасовой прогноз выработки на 48 часов
            (доля номинала 0..1, интервал p10–p90) + автоматический анализ. Возвращает
            analysis.status, analysis.recommended_action, weather_hash и путь к CSV.""")
    public String runPowerForecast(
            @ToolParam(description = "Дата выпуска D, YYYY-MM-DD") String issueDate) {
        Map<String, Object> a = args(issueDate);
        a.put("include_hourly", false);   // 48 значений лежат в CSV, не тратим контекст LLM
        return call("run_power_forecast", a);
    }

    @Tool(description = """
            Проверяет уже построенный прогноз: полнота, диапазоны, разброс моделей погоды, рампы.
            recommended_action: accept | accept_and_recheck_on_update | rerun_with_fallback.""")
    public String analyzeForecast(@ToolParam(description = "Дата выпуска D, YYYY-MM-DD") String issueDate) {
        return call("analyze_forecast", Map.of("issue_date", issueDate));
    }

    @Tool(description = """
            Проверяет, обновились ли погодные данные по сравнению с previousHash.
            Если updated=true — нужно повторно вызвать runPowerForecast.""")
    public String checkWeatherUpdate(
            @ToolParam(description = "Дата выпуска D, YYYY-MM-DD") String issueDate,
            @ToolParam(description = "weather_hash из предыдущего прогноза") String previousHash) {
        Map<String, Object> a = args(issueDate);
        a.put("previous_hash", previousHash);
        return call("check_weather_update", a);
    }

    @Tool(description = "Сравнивает прогноз с фактической выработкой (если факт уже есть): nMAE, nRMSE, bias.")
    public String evaluateForecast(@ToolParam(description = "Дата выпуска D, YYYY-MM-DD") String issueDate) {
        return call("evaluate_forecast", Map.of("issue_date", issueDate));
    }

    @Tool(description = "Паспорт ML-модели: период обучения, метрики валидации, важные признаки.")
    public String getModelInfo() {
        return call("get_model_info", Map.of());
    }

    /** Запасной вариант: переключение на живой прогноз погоды, если архив недоступен. */
    @Tool(description = """
            Запасной расчёт на свежем (live) прогнозе погоды. Использовать только если
            recommended_action = rerun_with_fallback и дата выпуска — сегодня.""")
    public String runPowerForecastLive(@ToolParam(description = "Дата выпуска D, YYYY-MM-DD") String issueDate) {
        return call("run_power_forecast",
                Map.of("issue_date", issueDate, "weather_source", "live", "include_hourly", false));
    }
}
