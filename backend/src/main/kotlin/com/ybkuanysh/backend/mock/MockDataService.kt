package com.ybkuanysh.backend.mock

import com.ybkuanysh.backend.dto.AgentLogResponse
import com.ybkuanysh.backend.dto.AgentStep
import com.ybkuanysh.backend.dto.AgentStepStatus
import com.ybkuanysh.backend.dto.DailyMetric
import com.ybkuanysh.backend.dto.ForecastPoint
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.RunForecastResponse
import com.ybkuanysh.backend.dto.Turbine
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class NotFoundException(message: String) : RuntimeException(message)

/**
 * Детерминированный генератор мок-данных: одинаковые параметры запроса всегда дают одинаковый ответ.
 * Погода и факт зависят только от (турбина, час), поэтому прогнозы с разных дат согласованы между собой.
 */
@Service
class MockDataService(private val clock: Clock) {

    val turbines = listOf(
        Turbine("t1", "Турбина 1", 43.2380, 76.9450),
        Turbine("t2", "Турбина 2", 43.2415, 76.9502),
    )

    private val manualRuns = ConcurrentHashMap<String, ManualRun>()

    fun requireTurbine(turbineId: String): Turbine =
        turbines.find { it.id == turbineId } ?: throw NotFoundException("Turbine not found: turbineId=$turbineId")

    fun forecast(turbineId: String, date: LocalDate, horizonHours: Int): ForecastResponse {
        requireTurbine(turbineId)
        val issuedAt = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val points = (1..horizonHours).map { lead ->
            val ts = issuedAt.plus(lead.toLong(), ChronoUnit.HOURS)
            val wind = windSpeed(turbineId, ts)
            val windErr = gaussian(turbineId, "fc", issuedAt.epochSecond, ts.epochSecond) * (0.2 + 0.012 * lead)
            ForecastPoint(
                timestamp = ts,
                predictedPower = r3(powerCurve(wind + windErr)),
                actualPower = if (ts.isBefore(ACTUALS_UNTIL)) r3(actualPower(turbineId, ts)) else null,
                windSpeed = r1((wind + windErr).coerceAtLeast(0.0)),
                temperature = r1(temperature(turbineId, ts)),
            )
        }
        return ForecastResponse(turbineId, issuedAt, horizonHours, points)
    }

    fun metrics(turbineId: String?, from: LocalDate, to: LocalDate): MetricsResponse {
        val ids = if (turbineId != null) listOf(requireTurbine(turbineId).id) else turbines.map { it.id }
        val allErr = mutableListOf<Double>()
        val allPct = mutableListOf<Double>()
        val baselineErr = mutableListOf<Double>()
        val byDay = mutableListOf<DailyMetric>()

        var day = from
        while (!day.isAfter(to)) {
            val dayErr = mutableListOf<Double>()
            for (id in ids) {
                val fc = forecast(id, day, 24)
                val persistence = actualPower(id, fc.forecastIssuedAt)
                for (p in fc.points) {
                    val actual = p.actualPower ?: continue
                    val e = p.predictedPower - actual
                    dayErr += e
                    baselineErr += persistence - actual
                    if (actual > 0.1) allPct += abs(e) / actual
                }
            }
            if (dayErr.isNotEmpty()) {
                byDay += DailyMetric(day, r3(mae(dayErr)), r3(rmse(dayErr)))
                allErr += dayErr
            }
            day = day.plusDays(1)
        }

        return MetricsResponse(
            turbineId = turbineId,
            periodFrom = from,
            periodTo = to,
            mae = r3(mae(allErr)),
            rmse = r3(rmse(allErr)),
            mape = r1(if (allPct.isEmpty()) 0.0 else allPct.average() * 100),
            baselineMae = r3(mae(baselineErr)),
            byDay = byDay,
        )
    }

    fun agentLog(date: LocalDate, turbineId: String?): AgentLogResponse {
        turbineId?.let { requireTurbine(it) }
        val manual = if (turbineId != null) manualRuns[runKey(date, turbineId)]
        else manualRuns.values.filter { it.date == date }.maxByOrNull { it.startedAt }
        return manual?.let { manualLog(it) } ?: scheduledLog(date, turbineId)
    }

    fun runCycle(turbineId: String, date: LocalDate, horizonHours: Int): RunForecastResponse {
        requireTurbine(turbineId)
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val run = ManualRun("cycle_$now", turbineId, date, horizonHours, now)
        manualRuns[runKey(date, turbineId)] = run
        return RunForecastResponse(run.cycleId, "processing")
    }

    // --- agent log ---

    private data class ManualRun(
        val cycleId: String,
        val turbineId: String,
        val date: LocalDate,
        val horizonHours: Int,
        val startedAt: Instant,
    )

    private fun runKey(date: LocalDate, turbineId: String) = "$date|$turbineId"

    private fun stepTemplates(horizonHours: Int, turbineCount: Int) = listOf(
        "Получение погодных данных" to "Источник: Open-Meteo archive, точек координат: $turbineCount",
        "Подготовка данных" to "Нормализация, агрегация по часам",
        "Запуск модели прогнозирования" to "Модель: LightGBM v3, горизонт ${horizonHours}ч",
        "Анализ результата" to "Аномалий не обнаружено",
    )

    /** Плановый цикл в 00:00 UTC. Раз в неделю — ретрай получения погоды, чтобы фронт видел статус retrying. */
    private fun scheduledLog(date: LocalDate, turbineId: String?): AgentLogResponse {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val turbineCount = if (turbineId != null) 1 else turbines.size
        val templates = stepTemplates(48, turbineCount)
        val withRetry = date.dayOfMonth % 7 == 3
        val steps = mutableListOf<AgentStep>()
        var t = start.plusSeconds(5)
        if (withRetry) {
            steps += AgentStep(templates[0].first, AgentStepStatus.retrying, t, "Timeout от Open-Meteo, повтор через 10с (попытка 1/3)")
            t = t.plusSeconds(10)
        }
        templates.forEachIndexed { i, (name, details) ->
            if (i > 0) t = t.plusSeconds(if (i == 2) 5 else 2)
            steps += AgentStep(name, AgentStepStatus.success, t, details)
        }
        return AgentLogResponse(date, "cycle_$start", steps)
    }

    /** Ручной запуск: шаги «проходят» по одному каждые STEP_DURATION — удобно для поллинга с фронта. */
    private fun manualLog(run: ManualRun): AgentLogResponse {
        val elapsed = Duration.between(run.startedAt, clock.instant())
        val done = (elapsed.toMillis() / STEP_DURATION.toMillis()).toInt()
        val steps = stepTemplates(run.horizonHours, 1).take(done + 1).mapIndexed { i, (name, details) ->
            val finished = i < done
            AgentStep(
                stepName = name,
                status = if (finished) AgentStepStatus.success else AgentStepStatus.running,
                timestamp = run.startedAt.plus(STEP_DURATION.multipliedBy(i.toLong())),
                details = if (finished) details else null,
            )
        }
        return AgentLogResponse(run.date, run.cycleId, steps)
    }

    // --- синтетическая физика ---

    private fun windSpeed(turbineId: String, ts: Instant): Double {
        val h = ts.epochSecond / 3600.0
        val phase = turbineId.hashCode() % 10 * 0.1
        val v = 7.5 +
            2.5 * sin(2 * PI * h / 24 + phase) +
            3.0 * sin(2 * PI * h / (24 * 3.7)) +
            1.5 * sin(2 * PI * h / (24 * 9.3) + 1.0) +
            0.8 * gaussian(turbineId, "wind", ts.epochSecond)
        return v.coerceAtLeast(0.0)
    }

    private fun actualPower(turbineId: String, ts: Instant): Double =
        (powerCurve(windSpeed(turbineId, ts)) + 0.02 * gaussian(turbineId, "act", ts.epochSecond)).coerceIn(0.0, 1.0)

    private fun temperature(turbineId: String, ts: Instant): Double {
        val h = ts.epochSecond / 3600.0
        return -4.0 + 4.0 * sin(2 * PI * (h - 9) / 24) + 2.0 * sin(2 * PI * h / (24 * 6.1)) +
            0.5 * gaussian(turbineId, "temp", ts.epochSecond)
    }

    /** Упрощённая кривая мощности: cut-in 3 м/с, номинал 12 м/с, cut-out 25 м/с. Нормализовано в 0..1. */
    private fun powerCurve(v: Double): Double = when {
        v < 3.0 || v >= 25.0 -> 0.0
        v >= 12.0 -> 1.0
        else -> ((v - 3.0) / 9.0).pow(3)
    }

    private fun gaussian(vararg key: Any): Double = Random(key.contentHashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong()).nextGaussian()

    private fun mae(e: List<Double>) = if (e.isEmpty()) 0.0 else e.sumOf { abs(it) } / e.size
    private fun rmse(e: List<Double>) = if (e.isEmpty()) 0.0 else sqrt(e.sumOf { it * it } / e.size)
    private fun r1(x: Double) = round(x * 10) / 10
    private fun r3(x: Double) = round(x * 1000) / 1000

    companion object {
        /** Факт известен до конца backtest-периода (1–28 февраля 2026). */
        val ACTUALS_UNTIL: Instant = Instant.parse("2026-03-01T00:00:00Z")
        val STEP_DURATION: Duration = Duration.ofSeconds(2)
    }
}
