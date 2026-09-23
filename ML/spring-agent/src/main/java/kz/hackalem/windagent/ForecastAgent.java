package kz.hackalem.windagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Агент: LLM + системная инструкция + инструменты.
 * LLM не обучается — она лишь решает, какие инструменты вызвать и в каком порядке,
 * и интерпретирует результаты. Цикл tool calling Spring AI выполняет сам.
 */
@Service
public class ForecastAgent {

    private static final String SYSTEM = """
            Ты — агент оперативного прогнозирования выработки ветроэлектростанции.
            Для выпуска прогноза на дату D выполняй цикл строго по шагам:
            1. getWeatherForecast(D): убедись, что доступна хотя бы одна модель погоды.
            2. runPowerForecast(D): получи прогноз и его analysis.
            3. Действуй по analysis.recommended_action:
               - accept → прогноз принят;
               - accept_and_recheck_on_update → вызови checkWeatherUpdate(D, weather_hash);
                 если updated=true — повтори runPowerForecast(D);
               - rerun_with_fallback → повтори runPowerForecast(D) один раз; если снова ошибка
                 и D — сегодняшняя дата, используй runPowerForecastLive(D), иначе сообщи об ошибке.
            4. Если факт уже доступен — вызови evaluateForecast(D).
            Не выдумывай числа: используй только данные из ответов инструментов.
            Итог: краткий отчёт на русском — статус, коэффициент использования мощности по суткам,
            пиковый час, предупреждения, было ли обновление и пересчёт, путь к файлу прогноза.
            """;

    private final ChatClient chat;

    public ForecastAgent(ChatClient.Builder builder, WindForecastTools tools) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(tools)
                .build();
    }

    public String forecast(String issueDate) {
        return chat.prompt()
                .user("Выпусти прогноз выработки ВЭС. Дата выпуска: " + issueDate)
                .call()
                .content();
    }

    public String ask(String question) {
        return chat.prompt().user(question).call().content();
    }
}
