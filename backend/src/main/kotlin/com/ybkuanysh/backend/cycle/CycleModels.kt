package com.ybkuanysh.backend.cycle

import com.ybkuanysh.backend.dto.AgentStep
import com.ybkuanysh.backend.dto.CycleStatus
import com.ybkuanysh.backend.dto.ForecastResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Один прогон цикла агента для турбины и дня выпуска: шаги и итоговый прогноз. */
data class CycleRecord(
    val cycleId: String,
    val turbineId: String,
    val issueDate: LocalDate,
    val horizonHours: Int,
    val status: CycleStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val steps: List<AgentStep>,
    /** Прогноз с отчётом агента; null — цикл ещё идёт или провалился. */
    val forecast: ForecastResponse?,
    val reportSource: String?,
)

@ConfigurationProperties("cycle")
data class CycleProperties(
    /** Куда сохранять циклы (JSON); пусто — только в памяти. */
    val storeDir: String = "../data/cycles",
    /** Писать отчёт через LLM; false — шаблон (для запуска без Ollama). */
    val llmReport: Boolean = true,
    /** false — цикл выполняется в потоке запроса (тесты). */
    val async: Boolean = true,
    val mlRetries: Int = 2,
    val retryDelay: Duration = Duration.ofSeconds(1),
    /** Изменение средней мощности к предыдущему выпуску, которое агент считает существенным. */
    val significantChange: Double = 0.05,
)
