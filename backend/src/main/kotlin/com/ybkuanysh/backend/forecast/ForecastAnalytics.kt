package com.ybkuanysh.backend.forecast

import com.ybkuanysh.backend.dto.AlertSeverity
import com.ybkuanysh.backend.dto.AlertType
import com.ybkuanysh.backend.dto.ForecastAlert
import com.ybkuanysh.backend.dto.ForecastPoint
import com.ybkuanysh.backend.dto.ForecastSummary
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.round

/** Сводка, алерты по правилам и шаблонный отчёт — общие для реального прогноза и моков. */
object ForecastAnalytics {

    const val LOW_POWER = 0.05
    private const val RAMP = 0.3
    private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneOffset.UTC)

    fun summary(points: List<ForecastPoint>): ForecastSummary {
        val peak = points.maxBy { it.predictedPower }
        return ForecastSummary(
            meanPower = r3(points.map { it.predictedPower }.average()),
            minPower = points.minOf { it.predictedPower },
            maxPower = peak.predictedPower,
            maxPowerAt = peak.timestamp,
            fullLoadHours = r1(points.sumOf { it.predictedPower }),
            lowPowerHours = points.count { it.predictedPower < LOW_POWER },
        )
    }

    /**
     * Алерты по точкам. [bandRule] — «широкий интервал P10–P90»: реальному прогнозу не нужен,
     * ML сам сообщает об этом (WIDE_INTERVAL).
     */
    fun ruleAlerts(points: List<ForecastPoint>, bandRule: Boolean): List<ForecastAlert> {
        val result = mutableListOf<ForecastAlert>()
        fun flag(type: AlertType, severity: AlertSeverity, minHours: Int, message: String, test: (ForecastPoint, Int) -> Boolean) {
            var start = -1
            for (i in 0..points.size) {
                val on = i < points.size && test(points[i], i)
                if (on && start < 0) start = i
                if (!on && start >= 0) {
                    if (i - start >= minHours) {
                        result += ForecastAlert(type, severity, points[start].timestamp, points[i - 1].timestamp, message)
                    }
                    start = -1
                }
            }
        }
        flag(AlertType.storm_cutout, AlertSeverity.critical, 1, "Ветер близок к штормовому отключению турбины (≥ 23 м/с)") { p, _ ->
            (p.windSpeed ?: 0.0) >= 23.0
        }
        flag(AlertType.icing, AlertSeverity.warning, 3, "Риск обледенения лопастей: температура около 0 °C") { p, _ ->
            p.temperature != null && p.temperature in -2.0..1.0 && (p.windSpeed ?: 0.0) > 3.0
        }
        flag(AlertType.ramp_down, AlertSeverity.warning, 1, "Резкий спад выработки (≥ 0.3 за 3 ч)") { p, i ->
            i >= 3 && points[i - 3].predictedPower - p.predictedPower >= RAMP
        }
        flag(AlertType.ramp_up, AlertSeverity.info, 1, "Резкий рост выработки (≥ 0.3 за 3 ч)") { p, i ->
            i >= 3 && p.predictedPower - points[i - 3].predictedPower >= RAMP
        }
        if (bandRule) {
            flag(AlertType.low_confidence, AlertSeverity.info, 3, "Высокая неопределённость прогноза (P90 − P10 ≥ 0.5)") { p, _ ->
                p.p90 != null && p.p10 != null && p.p90 - p.p10 >= 0.5
            }
        }
        return result.sortedBy { it.from }
    }

    /** Шаблонный отчёт; в цикле агента его заменяет текст LLM. */
    fun templateReport(s: ForecastSummary, alerts: List<ForecastAlert>): String {
        val main = "Средняя мощность ${s.meanPower}, пик ${s.maxPower} в ${HOUR.format(s.maxPowerAt)} UTC, " +
            "часов с выработкой ниже $LOW_POWER: ${s.lowPowerHours}."
        if (alerts.isEmpty()) return "$main Предупреждений нет."
        val list = alerts.joinToString("; ") { "${it.message} (${HOUR.format(it.from)}–${HOUR.format(it.to)} UTC)" }
        return "$main Предупреждения: $list."
    }

    fun r1(x: Double) = round(x * 10) / 10
    fun r3(x: Double) = round(x * 1000) / 1000
}
