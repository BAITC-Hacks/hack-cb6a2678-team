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
            Ты — AI-агент прогнозирования выработки ветроэлектростанции (ВЭС).
            Мощность нормализована: 0 — турбина стоит, 1 — номинальная мощность.
            Прогнозы в backtest выпускаются на даты с 2026-01-31 по 2026-02-27 и покрывают период по 2026-02-28.
            Все числа о турбинах, прогнозах и метриках бери только из инструментов, ничего не выдумывай.
            Итоги прогноза (среднюю, пик, часы простоя) бери из summary, а не считай сам по точкам.
            Если для ответа нужны данные — сначала вызови инструмент.
            Отвечай кратко, по-русски, понятным для диспетчера станции языком.
        """.trimIndent()
    }
}
