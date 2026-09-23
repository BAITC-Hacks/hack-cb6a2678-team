package com.ybkuanysh.backend.metrics

import com.ybkuanysh.backend.api.BadRequestException
import com.ybkuanysh.backend.dto.DailyMetric
import com.ybkuanysh.backend.dto.LeadTimeMetric
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.forecast.ForecastAnalytics.r1
import com.ybkuanysh.backend.forecast.ForecastAnalytics.r3
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.mock.MockDataService
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Метрики качества на отложенном тесте ML (январь 2026): факта за февраль в данных нет.
 * Тестовая модель обучена строго до начала теста, поэтому метрики честные.
 *
 * Основные метрики — по первым суткам прогноза (D+1), byLeadTime — по всем 48 часам горизонта.
 * Бейзлайны: persistence — факт за последний полный час перед выпуском (11:00–12:00 местного) на всё окно;
 * кривая мощности — прогноз ветра через эмпирическую кривую мощности, без ML (считает ML-сервис).
 */
@Service
class MetricsService(
    private val evaluations: EvaluationRepository,
    private val scada: ScadaRepository,
    private val props: MlProperties,
    private val turbines: MockDataService,
) {

    /** Период отложенного теста (местные сутки) — для /api/meta. */
    fun period(): Pair<LocalDate, LocalDate>? = runCatching {
        evaluations.evaluation(ForecastService.siteOf("t1")).rows.map { it.targetDate }.let { it.min() to it.max() }
    }.getOrNull()

    fun metrics(turbineId: String?, from: LocalDate, to: LocalDate): MetricsResponse {
        val ids = if (turbineId != null) listOf(turbines.requireTurbine(turbineId).id) else turbines.turbines.map { it.id }
        val scored = ids.flatMap { id ->
            val site = ForecastService.siteOf(id)
            val evaluation = evaluations.evaluation(site)
            val power = scada.hourlyPower(site)
            evaluation.rows
                .filter { it.actual != null && !it.targetDate.isBefore(from) && !it.targetDate.isAfter(to) }
                .map { row ->
                    val issueDay = row.targetDate.minusDays(row.leadDay.toLong())
                    Scored(row, power[issueDay.atTime(props.issueHourLocal - 1, 0)], evaluation.modelVersion, evaluation.source)
                }
        }
        if (scored.isEmpty()) {
            val available = period()?.let { "${it.first}…${it.second}" } ?: "нет"
            throw BadRequestException("Нет данных отложенного теста за $from…$to; доступно: $available")
        }

        val day1 = scored.filter { it.row.leadDay == 1 }
        val err = day1.map { it.row.forecast - it.row.actual!! }
        val persistence = day1.mapNotNull { s -> s.persistence?.let { it - s.row.actual!! } }
        val powerCurve = day1.mapNotNull { s -> s.row.pcBaseline?.let { it - s.row.actual!! } }

        return MetricsResponse(
            turbineId = turbineId,
            periodFrom = from,
            periodTo = to,
            mae = r3(mae(err)),
            rmse = r3(rmse(err)),
            mape = r1(day1.filter { it.row.actual!! > 0.1 }.map { abs(it.row.forecast - it.row.actual!!) / it.row.actual!! }.average() * 100),
            baselineMae = persistence.takeIf { it.isNotEmpty() }?.let { r3(mae(it)) },
            powerCurveBaselineMae = powerCurve.takeIf { it.isNotEmpty() }?.let { r3(mae(it)) },
            intervalCoverage = r3(day1.count { it.row.actual!! in it.row.p10..it.row.p90 }.toDouble() / day1.size),
            bias = r3(err.average()),
            sampleHours = day1.size,
            evaluationModelVersion = scored.firstNotNullOfOrNull { it.modelVersion },
            evaluationSource = scored.first().source,
            byDay = day1.groupBy { it.row.targetDate }.toSortedMap().map { (date, rows) ->
                val e = rows.map { it.row.forecast - it.row.actual!! }
                DailyMetric(date, r3(mae(e)), r3(rmse(e)))
            },
            byLeadTime = scored.groupBy { (it.row.leadDay - 1) * 24 + it.row.localHour + 1 }.toSortedMap().map { (hour, rows) ->
                LeadTimeMetric(hour, r3(mae(rows.map { it.row.forecast - it.row.actual!! })))
            },
        )
    }

    private data class Scored(val row: EvalRow, val persistence: Double?, val modelVersion: String?, val source: String)

    private fun mae(e: List<Double>) = e.sumOf { abs(it) } / e.size
    private fun rmse(e: List<Double>) = sqrt(e.sumOf { it * it } / e.size)
}
