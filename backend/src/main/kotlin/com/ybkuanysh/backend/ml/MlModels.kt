package com.ybkuanysh.backend.ml

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

/*
 * Ответы ML-сервиса по contracts/ml-service.openapi.yaml (v2.0).
 * Поля, помеченные в контракте «v2», nullable: до выхода новой версии ML их нет.
 */

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlForecastRequest(
    val site: String,
    val issueDate: String,
    val weatherSource: String = "previous_runs",
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlForecastResponse(
    val site: String,
    val issueDate: String,
    val issuedAtUtc: String? = null,
    val localTz: String? = null,
    val targetStart: String? = null,
    val targetEnd: String? = null,
    val targetStartUtc: String? = null,
    val targetEndUtc: String? = null,
    val unit: String? = null,
    val modelVersion: String? = null,
    val modelTrainedAt: String? = null,
    val weatherSource: String? = null,
    val weatherModels: List<String>? = null,
    val weatherIssuedBeforeUtc: String? = null,
    val leakageMode: String? = null,
    val weatherHash: String? = null,
    val analysis: MlAnalysis? = null,
    val hourly: List<MlHour> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlHour(
    val time: String? = null,
    val timeUtc: String? = null,
    val leadDay: Int,
    val forecast: Double,
    val p10: Double,
    val p50: Double,
    val p90: Double,
    val windSpeed100m: Double? = null,
    val windSpread: Double? = null,
    val temperature2m: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlAnalysis(
    val status: String? = null,
    val recommendedAction: String? = null,
    val issues: List<MlIssue> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlIssue(
    val level: String,
    val code: String,
    val message: String,
    val fromUtc: String? = null,
    val toUtc: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MlModelInfo(
    val site: String,
    val modelVersion: String? = null,
    /** Версия формата модели в текущем ML (до v2 контракта) — запасной вариант для modelVersion. */
    val version: String? = null,
    val trainedAt: String? = null,
    val trainedUntilUtc: String? = null,
)

class MlUnavailableException(message: String) : RuntimeException(message)
