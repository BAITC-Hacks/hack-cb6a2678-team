package com.ybkuanysh.backend.cycle

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Component

/** Пишет отчёт для диспетчера по готовым выводам; null — не получилось (цикл возьмёт шаблон). */
fun interface ReportWriter {
    fun write(facts: List<String>): String?
}

@Component
class LlmReportWriter(builder: ChatClient.Builder, private val props: CycleProperties) : ReportWriter {

    private val log = LoggerFactory.getLogger(javaClass)

    // Отдельный клиент без инструментов: отчёт пишется только по переданным фактам
    private val chatClient = builder.defaultSystem(SYSTEM_PROMPT).build()

    override fun write(facts: List<String>): String? {
        if (!props.llmReport) return null
        return try {
            chatClient.prompt().user(facts.joinToString("\n")).call().content()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: RuntimeException) {
            log.warn("LLM report failed, using template: {}", e.message)
            null
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            Ты — AI-агент прогнозирования выработки ветроэлектростанции. Напиши отчёт для диспетчера станции
            по фактам из сообщения: 3–5 связных предложений, по-русски, без заголовков, списков и markdown.
            1) Сколько выработки ожидается по каждому дню (средняя мощность и пик с временем).
            2) Самые важные предупреждения (обледенение, шторм, резкий спад) с временем.
            3) Как изменился прогноз — только если в сообщении есть строка «Сутки …»; про другие дни об изменениях не говори.
            Используй только числа и факты из сообщения, ничего не добавляй, не пересчитывай и не выдумывай.
            Мощность — в процентах номинала, время — местное.

            Пример формы; всё в угловых скобках замени значениями из сообщения, предупреждения бери только из сообщения:
            <Дата 1> турбина будет работать в среднем на <X> % номинала с пиком <Y> % в <время>, <дата 2> — в среднем
            <Z> %. <Предупреждение из сообщения> с <время> до <время>. По сравнению с предыдущим выпуском прогноз
            на <дата> <изменился/почти не изменился> (<изменение из сообщения>).
        """.trimIndent()
    }
}

/**
 * Самопроверка отчёта LLM: каждое время «ЧЧ:ММ» и каждый процент из текста должны встречаться в фактах.
 * Возвращает значения, которых в фактах нет (пусто — отчёт прошёл проверку).
 */
fun unsupportedValues(report: String, facts: List<String>): List<String> {
    val source = facts.joinToString(" ")
    val times = TIME.findAll(report).map { it.value.padStart(5, '0') }.filter { it !in source }
    val percents = PERCENT.findAll(report).map { it.groupValues[1] }
        .filter { p -> !Regex("""(?<![\d.,])${Regex.escape(p)}\s?%""").containsMatchIn(source) }
        .map { "$it %" }
    return (times + percents).distinct().toList()
}

private val TIME = Regex("""\b\d{1,2}:\d{2}\b""")
private val PERCENT = Regex("""(\d+(?:[.,]\d+)?)\s?%""")
