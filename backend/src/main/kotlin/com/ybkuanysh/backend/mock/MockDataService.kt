package com.ybkuanysh.backend.mock

import com.ybkuanysh.backend.dto.AgentLogResponse
import com.ybkuanysh.backend.dto.AgentStep
import com.ybkuanysh.backend.dto.AgentStepStatus
import com.ybkuanysh.backend.dto.DailyMetric
import com.ybkuanysh.backend.dto.ForecastPoint
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.ForecastRevision
import com.ybkuanysh.backend.dto.ForecastRevisionsResponse
import com.ybkuanysh.backend.dto.LeadTimeMetric
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.RunForecastResponse
import com.ybkuanysh.backend.dto.Turbine
import com.ybkuanysh.backend.forecast.ForecastAnalytics
import com.ybkuanysh.backend.weather.WeatherService
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
class MockDataService(private val clock: Clock, private val weather: WeatherService) {

    val turbines = listOf(
        // Координаты из ТЗ (ссылки Google Maps)
        Turbine("t1", "Турбина 1", 43.645150, 78.535604),
        Turbine("t2", "Турбина 2", 43.643198, 78.538828),
    )

    private val manualRuns = ConcurrentHashMap<String, ManualRun>()

    fun requireTurbine(turbineId: String): Turbine =
        turbines.find { it.id == turbineId } ?: throw NotFoundException("Turbine not found: turbineId=$turbineId")

    fun forecast(turbineId: String, date: LocalDate, horizonHours: Int, revision: Int = 1): ForecastResponse {
        requireTurbine(turbineId)
        val issuedAt = issuedAt(date, revision)
        val weatherIssuedAt = weather.latestAvailableRun(issuedAt)
        val points = (1..horizonHours).map { lead ->
            val ts = issuedAt.plus(lead.toLong(), ChronoUnit.HOURS)
            // Ошибка прогноза ветра растёт с заблаговременностью от выпуска погодного прогона
            val weatherLead = Duration.between(weatherIssuedAt, ts).toHours()
            val sigma = 0.4 + 0.02 * weatherLead
            val wind = (windSpeed(turbineId, ts) + gaussian(turbineId, "fc", weatherIssuedAt.epochSecond, ts.epochSecond) * sigma)
                .coerceAtLeast(0.0)
            val band = listOf(powerCurve(wind - Z80 * sigma), powerCurve(wind + Z80 * sigma))
            ForecastPoint(
                timestamp = ts,
                predictedPower = r3(powerCurve(wind)),
                p10 = r3(band.min()),
                p90 = r3(band.max()),
                actualPower = if (ts.isBefore(ACTUALS_UNTIL)) r3(actualPower(turbineId, ts)) else null,
                windSpeed = r1(wind),
                temperature = r1(temperature(turbineId, ts)),
            )
        }
        val summary = ForecastAnalytics.summary(points)
        val alerts = ForecastAnalytics.ruleAlerts(points, bandRule = true)
        return ForecastResponse(
            turbineId = turbineId,
            forecastIssuedAt = issuedAt,
            revision = revision,
            horizonHours = horizonHours,
            weatherSource = WEATHER_SOURCE,
            weatherIssuedAt = weatherIssuedAt,
            modelVersion = MODEL_VERSION,
            summary = summary,
            alerts = alerts,
            agentReport = ForecastAnalytics.templateReport(summary, alerts),
            points = points,
        )
    }

    fun revisions(turbineId: String, date: LocalDate): ForecastRevisionsResponse {
        requireTurbine(turbineId)
        var previous: Double? = null
        val revisions = (1..REVISIONS_PER_DAY).map { rev ->
            val fc = forecast(turbineId, date, 48, rev)
            val mean = fc.summary.meanPower
            val change = previous?.let { if (it > 0.0) r1((mean - it) / it * 100) else null }
            previous = mean
            ForecastRevision(rev, fc.forecastIssuedAt, fc.weatherIssuedAt, mean, change)
        }
        return ForecastRevisionsResponse(turbineId, date, revisions)
    }

    /** Основные метрики — по горизонту 1–24 ч первой (плановой) ревизии; byLeadTime — по 1–48 ч. */
    fun metrics(turbineId: String?, from: LocalDate, to: LocalDate): MetricsResponse {
        val ids = if (turbineId != null) listOf(requireTurbine(turbineId).id) else turbines.map { it.id }
        val allErr = mutableListOf<Double>()
        val allPct = mutableListOf<Double>()
        val baselineErr = mutableListOf<Double>()
        val powerCurveErr = mutableListOf<Double>()
        var covered = 0
        val byLead = Array(48) { mutableListOf<Double>() }
        val byDay = mutableListOf<DailyMetric>()

        var day = from
        while (!day.isAfter(to)) {
            val dayErr = mutableListOf<Double>()
            for (id in ids) {
                val fc = forecast(id, day, 48)
                val persistence = actualPower(id, fc.forecastIssuedAt)
                fc.points.forEachIndexed { i, p ->
                    val actual = p.actualPower ?: return@forEachIndexed
                    val e = p.predictedPower - actual
                    byLead[i] += e
                    if (i >= 24) return@forEachIndexed
                    dayErr += e
                    baselineErr += persistence - actual
                    // «Сырая» кривая мощности по ветру на 10 м без поправки на высоту ступицы
                    powerCurveErr += powerCurve((p.windSpeed ?: 0.0) * 0.85) - actual
                    if (actual in p.p10!!..p.p90!!) covered++
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
            powerCurveBaselineMae = r3(mae(powerCurveErr)),
            intervalCoverage = if (allErr.isEmpty()) null else r3(covered.toDouble() / allErr.size),
            byDay = byDay,
            byLeadTime = byLead.mapIndexedNotNull { i, e -> if (e.isEmpty()) null else LeadTimeMetric(i + 1, r3(mae(e))) },
        )
    }

    fun agentLog(date: LocalDate, turbineId: String?, revision: Int = 1): AgentLogResponse {
        turbineId?.let { requireTurbine(it) }
        val manual = if (turbineId != null) manualRuns[runKey(date, turbineId)]
        else manualRuns.values.filter { it.date == date }.maxByOrNull { it.startedAt }
        return manual?.let { manualLog(it) } ?: scheduledLog(date, turbineId, revision)
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

    private data class StepTemplate(val name: String, val tool: String?, val details: String)

    private fun runKey(date: LocalDate, turbineId: String) = "$date|$turbineId"

    private fun issuedAt(date: LocalDate, revision: Int): Instant =
        date.atStartOfDay().toInstant(ZoneOffset.UTC).plus(REVISION_STEP.multipliedBy(revision - 1L))

    private fun stepTemplates(horizonHours: Int, turbineCount: Int, weatherIssuedAt: Instant) = listOf(
        StepTemplate(
            "Получение прогноза погоды", "fetchWeather",
            "$WEATHER_SOURCE, прогон от $weatherIssuedAt (не позже момента прогноза), точек координат: $turbineCount",
        ),
        StepTemplate("Подготовка признаков", "prepareFeatures", "Ветер на высоте ступицы, плотность воздуха, календарные признаки"),
        StepTemplate("Запуск модели прогнозирования", "predict", "Модель: $MODEL_VERSION, горизонт ${horizonHours}ч, квантили P10/P50/P90"),
        StepTemplate("Анализ результата", "validate", "Диапазон [0,1] соблюдён, резких скачков нет"),
        StepTemplate("Отчёт агента", null, "Сформирован текстовый отчёт и список предупреждений"),
    )

    /** Плановый цикл в момент ревизии. Раз в неделю — ретрай получения погоды, чтобы фронт видел статус retrying. */
    private fun scheduledLog(date: LocalDate, turbineId: String?, revision: Int): AgentLogResponse {
        val start = issuedAt(date, revision)
        val weatherIssuedAt = weather.latestAvailableRun(start)
        val turbineCount = if (turbineId != null) 1 else turbines.size
        val templates = stepTemplates(48, turbineCount, weatherIssuedAt)
        val withRetry = date.dayOfMonth % 7 == 3
        val steps = mutableListOf<AgentStep>()
        var t = start.plusSeconds(5)
        if (withRetry) {
            steps += AgentStep(
                templates[0].name, AgentStepStatus.retrying, t,
                "Timeout от Open-Meteo, повтор через 10с (попытка 1/3)", templates[0].tool, null,
            )
            t = t.plusSeconds(10)
        }
        templates.forEachIndexed { i, tpl ->
            if (i > 0) t = t.plusSeconds(if (i == 2) 5 else 2)
            steps += AgentStep(tpl.name, AgentStepStatus.success, t, tpl.details, tpl.tool, weatherIssuedAt.takeIf { i == 0 })
        }
        return AgentLogResponse(date, "cycle_$start", revision, start, steps)
    }

    /** Ручной запуск: шаги «проходят» по одному каждые STEP_DURATION — удобно для поллинга с фронта. */
    private fun manualLog(run: ManualRun): AgentLogResponse {
        val issuedAt = issuedAt(run.date, 1)
        val weatherIssuedAt = weather.latestAvailableRun(issuedAt)
        val elapsed = Duration.between(run.startedAt, clock.instant())
        val done = (elapsed.toMillis() / STEP_DURATION.toMillis()).toInt()
        val steps = stepTemplates(run.horizonHours, 1, weatherIssuedAt).take(done + 1).mapIndexed { i, tpl ->
            val finished = i < done
            AgentStep(
                stepName = tpl.name,
                status = if (finished) AgentStepStatus.success else AgentStepStatus.running,
                timestamp = run.startedAt.plus(STEP_DURATION.multipliedBy(i.toLong())),
                details = if (finished) tpl.details else null,
                tool = tpl.tool,
                dataIssuedAt = weatherIssuedAt.takeIf { finished && i == 0 },
            )
        }
        return AgentLogResponse(run.date, run.cycleId, 1, issuedAt, steps)
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
        /** Прогнозы выпускаются «как если бы» на 00:00 UTC каждого дня: первый — на 31.01, последний покрывает 28.02. */
        val BACKTEST_FROM: LocalDate = LocalDate.parse("2026-01-31")
        val BACKTEST_TO: LocalDate = LocalDate.parse("2026-02-27")

        /** Факт известен до конца backtest-периода (по 28 февраля 2026 включительно). */
        val ACTUALS_UNTIL: Instant = Instant.parse("2026-03-01T00:00:00Z")
        val STEP_DURATION: Duration = Duration.ofSeconds(2)

        /** Ревизии: плановая в 00:00 UTC и пересчёты при выходе новых прогонов погоды каждые 6 ч. */
        const val REVISIONS_PER_DAY = 4
        val REVISION_STEP: Duration = Duration.ofHours(6)

        const val WEATHER_SOURCE = "open-meteo:ecmwf_ifs"
        const val MODEL_VERSION = "mock-v0"

        private const val Z80 = 1.2816 // квантиль N(0,1) для интервала P10–P90
    }
}
