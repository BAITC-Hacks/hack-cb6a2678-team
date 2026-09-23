package com.ybkuanysh.backend.dto

import com.ybkuanysh.backend.weather.WeatherForecast
import java.time.Instant
import java.time.LocalDate

data class Turbine(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class MetaResponse(
    val backtestFrom: LocalDate,
    val backtestTo: LocalDate,
    val horizons: List<Int>,
    val revisionsPerDay: Int,
    val timezone: String,
    val modelVersion: String,
    val llmModel: String,
)

data class ForecastPoint(
    val timestamp: Instant,
    val predictedPower: Double,
    val p10: Double?,
    val p90: Double?,
    val actualPower: Double?,
    val windSpeed: Double,
    val temperature: Double,
)

data class ForecastSummary(
    val meanPower: Double,
    val minPower: Double,
    val maxPower: Double,
    val maxPowerAt: Instant,
    val fullLoadHours: Double,
    val lowPowerHours: Int,
)

enum class AlertType { icing, storm_cutout, ramp_down, ramp_up, low_confidence }

enum class AlertSeverity { info, warning, critical }

data class ForecastAlert(
    val type: AlertType,
    val severity: AlertSeverity,
    val from: Instant,
    val to: Instant,
    val message: String,
)

data class ForecastResponse(
    val turbineId: String,
    val forecastIssuedAt: Instant,
    val revision: Int,
    val horizonHours: Int,
    val weatherSource: String,
    val weatherIssuedAt: Instant,
    val modelVersion: String,
    val summary: ForecastSummary,
    val alerts: List<ForecastAlert>,
    val agentReport: String?,
    val points: List<ForecastPoint>,
)

data class ForecastRevision(
    val revision: Int,
    val forecastIssuedAt: Instant,
    val weatherIssuedAt: Instant,
    val meanPower: Double,
    val changeVsPreviousPct: Double?,
)

data class ForecastRevisionsResponse(
    val turbineId: String,
    val date: LocalDate,
    val revisions: List<ForecastRevision>,
)

data class DailyMetric(
    val date: LocalDate,
    val mae: Double,
    val rmse: Double,
)

data class LeadTimeMetric(
    val leadHour: Int,
    val mae: Double,
)

data class MetricsResponse(
    val turbineId: String?,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val mae: Double,
    val rmse: Double,
    val mape: Double,
    val baselineMae: Double,
    val powerCurveBaselineMae: Double?,
    val intervalCoverage: Double?,
    val byDay: List<DailyMetric>,
    val byLeadTime: List<LeadTimeMetric>,
)

enum class AgentStepStatus { success, failed, retrying, running }

data class AgentStep(
    val stepName: String,
    val status: AgentStepStatus,
    val timestamp: Instant,
    val details: String?,
    val tool: String?,
    val dataIssuedAt: Instant?,
)

data class AgentLogResponse(
    val date: LocalDate,
    val cycleId: String,
    val revision: Int,
    val forecastIssuedAt: Instant,
    val steps: List<AgentStep>,
)

data class RunForecastRequest(
    val turbineId: String,
    val date: LocalDate,
    val horizonHours: Int? = null,
)

data class RunForecastResponse(
    val cycleId: String,
    val status: String,
)

data class WeatherResponse(
    val turbineId: String,
    val forecast: WeatherForecast,
)

data class ErrorResponse(
    val message: String,
)

data class AgentChatRequest(
    val message: String,
)

data class AgentToolCall(
    val tool: String,
    val args: Map<String, Any?>,
    val ok: Boolean,
    val error: String?,
)

data class AgentChatResponse(
    val answer: String,
    val model: String,
    val toolCalls: List<AgentToolCall>,
    val durationMs: Long,
)
