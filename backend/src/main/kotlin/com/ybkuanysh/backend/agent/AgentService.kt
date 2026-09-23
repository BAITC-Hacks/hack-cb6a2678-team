package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentChatResponse
import com.ybkuanysh.backend.dto.AgentToolCall
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Collections

@Service
class AgentService(
    builder: ChatClient.Builder,
    tools: AgentTools,
    @Value("\${spring.ai.ollama.chat.model}") private val model: String,
) {

    private val chatClient = builder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultTools(tools)
        .build()

    fun chat(message: String): AgentChatResponse {
        val started = System.currentTimeMillis()
        val trace = Collections.synchronizedList(mutableListOf<AgentToolCall>())
        val answer = chatClient.prompt()
            .user(message)
            .toolContext(mapOf(AgentTools.TRACE_KEY to trace))
            .call()
            .content()
            .orEmpty()
        return AgentChatResponse(answer, model, trace.toList(), System.currentTimeMillis() - started)
    }

    companion object {
        private val SYSTEM_PROMPT = """
            Ты — AI-агент прогнозирования выработки ветроэлектростанции (ВЭС) из двух турбин (t1, t2).
            Мощность нормализована: 0 — турбина стоит, 1 — номинальная мощность.
            Прогноз мощности делает ML-модель: выпуск каждый день в 12:00 местного времени на следующие двое суток.
            Период прогнозов — с 1 по 28 февраля 2026 года; фактической выработки за февраль нет, сравнить с фактом нельзя.
            Все числа о турбинах, прогнозах, погоде и метриках бери только из инструментов, ничего не выдумывай.
            Выработка — getForecast, погода (ветер, температура, обледенение, шторм) — getWeather,
            как менялся прогноз — getForecastRevisions. Запрашивай ровно те дни, о которых спросили (date и toDate).
            Отвечай по полю conclusions, не противоречь ему, не считай сам и не делай своих выводов о рисках.
            Ветер указывай в м/с, температуру в °C, мощность — в процентах номинала, время — местное.
            Если для ответа нужны данные — сначала вызови инструмент.
            Отвечай кратко, по-русски, понятным для диспетчера станции языком.
        """.trimIndent()
    }
}
