package com.ybkuanysh.backend.forecast

import com.ybkuanysh.backend.dto.AlertSeverity
import com.ybkuanysh.backend.dto.AlertType
import com.ybkuanysh.backend.dto.ForecastAlert
import com.ybkuanysh.backend.dto.ForecastPoint
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.ForecastRevision
import com.ybkuanysh.backend.dto.ForecastRevisionsResponse
import com.ybkuanysh.backend.ml.MlForecastResponse
import com.ybkuanysh.backend.ml.MlForecastSource
import com.ybkuanysh.backend.ml.MlIssue
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.ml.MlUnavailableException
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.weather.LeakageException
import com.ybkuanysh.backend.weather.WeatherService
import com.ybkuanysh.backend.weather.WeatherUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Прогноз выпуска в день D (12:00 местного) с точками и номером суток заблаговременности. */
data class IssuedForecast(val response: ForecastResponse, val leadDays: List<Int>)

/**
 * Реальный прогноз: ML-сервис (contracts/ml-service.openapi.yaml) → публичный ForecastResponse.
 *
 * Выпуск в день D покрывает сутки D+1 и D+2. Поля ML «v2», которых ещё нет, вычисляются здесь:
 * время в UTC — по local_tz, граница выпуска погоды — консервативно по режиму strict,
 * ветер и температура в точках — из клиента погоды бэкенда (прогноз, известный на момент выпуска).
 */
@Service
class ForecastService(
    private val ml: MlForecastSource,
    private val props: MlProperties,
    private val weather: WeatherService,
    private val turbines: TurbineRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Момент выпуска D 12:00 местного — как его задаёт ML (пока ML не отдаёт issued_at_utc сам). */
    fun issuedAt(issueDate: LocalDate): Instant = issueDate.atTime(props.issueHourLocal, 0).atZone(ZoneId.of(props.localTz)).toInstant()

    fun forecast(turbineId: String, issueDate: LocalDate, horizonHours: Int): ForecastResponse =
        issued(turbineId, issueDate, horizonHours).response

    fun issued(turbineId: String, issueDate: LocalDate, horizonHours: Int): IssuedForecast {
        val turbine = turbines.requireTurbine(turbineId)
        val ml = ml.forecast(siteOf(turbineId), issueDate)
        val tz = ZoneId.of(ml.localTz ?: props.localTz)
        val issuedAt = ml.issuedAtUtc?.let(Instant::parse)
            ?: issueDate.atTime(props.issueHourLocal, 0).atZone(tz).toInstant()
        val weatherIssuedAt = ml.weatherIssuedBeforeUtc?.let(Instant::parse) ?: strictWeatherBound(issueDate, tz)
        if (weatherIssuedAt.isAfter(issuedAt)) {
            throw LeakageException("ML использовал погоду, выпущенную в $weatherIssuedAt — позже момента прогноза $issuedAt")
        }

        val hours = ml.hourly.filter { it.leadDay <= horizonHours / 24 }
        if (hours.size != horizonHours) {
            throw MlUnavailableException("ML вернул ${hours.size} ч вместо $horizonHours для ${turbineId} на $issueDate")
        }
        val times = hours.map { h -> h.timeUtc?.let(Instant::parse) ?: LocalDateTime.parse(h.time!!.replace(' ', 'T')).atZone(tz).toInstant() }
        val backendWeather = if (hours.any { it.windSpeed100m == null || it.temperature2m == null }) {
            weatherAt(turbine.lat, turbine.lon, issuedAt, times.last())
        } else {
            emptyMap()
        }

        val points = hours.mapIndexed { i, h ->
            val w = backendWeather[times[i]]
            ForecastPoint(
                timestamp = times[i],
                predictedPower = ForecastAnalytics.r3(h.forecast),
                p10 = ForecastAnalytics.r3(h.p10),
                p90 = ForecastAnalytics.r3(h.p90),
                // Факта за период прогноза (февраль 2026) нет в данных
                actualPower = null,
                windSpeed = (h.windSpeed100m ?: w?.first)?.let { ForecastAnalytics.r1(it) },
                temperature = (h.temperature2m ?: w?.second)?.let { ForecastAnalytics.r1(it) },
            )
        }
        val summary = ForecastAnalytics.summary(points)
        val alerts = (ml.analysis?.issues.orEmpty().map { toAlert(it, points) } +
            ForecastAnalytics.ruleAlerts(points, bandRule = false)).sortedBy { it.from }

        val response = ForecastResponse(
            turbineId = turbineId,
            forecastIssuedAt = issuedAt,
            revision = 1,
            horizonHours = horizonHours,
            weatherSource = weatherSource(ml),
            weatherIssuedAt = weatherIssuedAt,
            modelVersion = modelVersion(ml),
            summary = summary,
            alerts = alerts,
            agentReport = ForecastAnalytics.templateReport(summary, alerts),
            points = points,
        )
        return IssuedForecast(response, hours.map { it.leadDay })
    }

    /**
     * Версии прогноза на целевые сутки X: из выпуска X−2 (как D+2) и из выпуска X−1 (как D+1, по более свежей погоде).
     * Это и есть «повторный расчёт при обновлении входных данных».
     */
    fun revisions(turbineId: String, targetDate: LocalDate): ForecastRevisionsResponse {
        turbines.requireTurbine(turbineId)
        var previous: Double? = null
        val revisions = listOf(2L to 2, 1L to 1).mapIndexedNotNull { i, (daysBefore, lead) ->
            val issued = try {
                issued(turbineId, targetDate.minusDays(daysBefore), 48)
            } catch (e: MlUnavailableException) {
                log.warn("Revision {} for {} {} unavailable: {}", i + 1, turbineId, targetDate, e.message)
                return@mapIndexedNotNull null
            }
            val dayPoints = issued.response.points.filterIndexed { idx, _ -> issued.leadDays[idx] == lead }
            val mean = ForecastAnalytics.r3(dayPoints.map { it.predictedPower }.average())
            val change = previous?.let { if (it > 0.0) ForecastAnalytics.r1((mean - it) / it * 100) else null }
            previous = mean
            ForecastRevision(i + 1, issued.response.forecastIssuedAt, issued.response.weatherIssuedAt, mean, change)
        }
        return ForecastRevisionsResponse(turbineId, targetDate, revisions)
    }

    fun modelVersion(turbineId: String): String? = try {
        ml.model(siteOf(turbineId)).let { it.modelVersion ?: "windml-${it.version ?: "?"}-${it.trainedAt?.take(10) ?: "?"}" }
    } catch (e: MlUnavailableException) {
        null
    }

    /**
     * Режим strict: для суток D+k берётся прогноз, сделанный за (k+1) суток до часа. Самый поздний такой
     * прогноз — для последнего часа каждых суток: (D−1) 23:00 местного.
     */
    private fun strictWeatherBound(issueDate: LocalDate, tz: ZoneId): Instant =
        issueDate.minusDays(1).atTime(23, 0).atZone(tz).toInstant()

    /** Ветер на 100 м и температура из клиента погоды бэкенда — прогноз, известный в момент выпуска. */
    private fun weatherAt(lat: Double, lon: Double, issuedAt: Instant, last: Instant): Map<Instant, Pair<Double?, Double?>> = try {
        val horizon = Duration.between(issuedAt, last).toHours().toInt() + 1
        weather.forecastAt(lat, lon, issuedAt, horizon).hours.associate { it.timestamp to (it.windSpeed100m to it.temperature2m) }
    } catch (e: WeatherUnavailableException) {
        log.warn("No backend weather for points: {}", e.message)
        emptyMap()
    }

    private fun toAlert(issue: MlIssue, points: List<ForecastPoint>): ForecastAlert {
        val (type, defaultSeverity) = when (issue.code) {
            "HIGH_NWP_DISAGREEMENT" -> AlertType.low_confidence to AlertSeverity.warning
            "WIDE_INTERVAL" -> AlertType.low_confidence to AlertSeverity.info
            "ML_VS_PHYSICS_GAP" -> AlertType.model_physics_gap to AlertSeverity.warning
            else -> AlertType.low_confidence to AlertSeverity.info
        }
        val severity = when (issue.level) {
            "critical" -> AlertSeverity.critical
            "info" -> AlertSeverity.info
            else -> defaultSeverity
        }
        return ForecastAlert(
            type = type,
            severity = severity,
            from = issue.fromUtc?.let(Instant::parse) ?: points.first().timestamp,
            to = issue.toUtc?.let(Instant::parse) ?: points.last().timestamp,
            message = issue.message,
        )
    }

    private fun weatherSource(ml: MlForecastResponse): String {
        val base = ml.weatherSource ?: "open-meteo-previous-runs"
        return ml.weatherModels?.takeIf { it.isNotEmpty() }?.let { "$base:${it.joinToString("+")}" } ?: base
    }

    private fun modelVersion(ml: MlForecastResponse): String =
        ml.modelVersion ?: ml.modelTrainedAt?.let { "windml-${it.take(10)}" } ?: "windml"

    companion object {
        /** Даты выпуска в backtest: выпуск 26.02 покрывает 27–28.02 (ТЗ: прогноз на следующие 24–48 ч). */
        val BACKTEST_FROM: LocalDate = LocalDate.parse("2026-01-31")
        val BACKTEST_TO: LocalDate = LocalDate.parse("2026-02-26")

        /** Каждые целевые сутки прогнозируются дважды: из выпусков X−2 и X−1. */
        const val VERSIONS_PER_TARGET_DAY = 2

        /** t1 ↔ turbine_1: идентификаторы публичного API и ML-сервиса. */
        fun siteOf(turbineId: String) = "turbine_" + turbineId.removePrefix("t")
    }
}
