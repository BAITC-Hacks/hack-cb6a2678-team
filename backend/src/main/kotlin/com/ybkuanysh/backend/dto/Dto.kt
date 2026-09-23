package com.ybkuanysh.backend.dto

import java.time.Instant
import java.time.LocalDate

data class Turbine(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class ForecastPoint(
    val timestamp: Instant,
    val predictedPower: Double,
    val actualPower: Double?,
    val windSpeed: Double,
    val temperature: Double,
)

data class ForecastResponse(
    val turbineId: String,
    val forecastIssuedAt: Instant,
    val horizonHours: Int,
    val points: List<ForecastPoint>,
)

data class DailyMetric(
    val date: LocalDate,
    val mae: Double,
    val rmse: Double,
)

data class MetricsResponse(
    val turbineId: String?,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val mae: Double,
    val rmse: Double,
    val mape: Double,
    val baselineMae: Double,
    val byDay: List<DailyMetric>,
)

enum class AgentStepStatus { success, failed, retrying, running }

data class AgentStep(
    val stepName: String,
    val status: AgentStepStatus,
    val timestamp: Instant,
    val details: String?,
)

data class AgentLogResponse(
    val date: LocalDate,
    val cycleId: String,
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

data class ErrorResponse(
    val message: String,
)
