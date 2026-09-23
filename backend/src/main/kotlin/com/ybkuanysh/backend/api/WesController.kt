package com.ybkuanysh.backend.api

import com.ybkuanysh.backend.dto.AgentLogResponse
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.ForecastRevisionsResponse
import com.ybkuanysh.backend.dto.MetaResponse
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.RunForecastRequest
import com.ybkuanysh.backend.dto.RunForecastResponse
import com.ybkuanysh.backend.dto.Turbine
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.mock.MockDataService
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@RestController
@RequestMapping("/api")
class WesController(
    private val mock: MockDataService,
    private val forecasts: ForecastService,
    private val ml: MlProperties,
    @Value("\${spring.ai.ollama.chat.model}") private val llmModel: String,
) {

    @GetMapping("/meta")
    fun getMeta() = MetaResponse(
        backtestFrom = ForecastService.BACKTEST_FROM,
        backtestTo = ForecastService.BACKTEST_TO,
        horizons = listOf(24, 48),
        revisionsPerDay = ForecastService.VERSIONS_PER_TARGET_DAY,
        timezone = "UTC",
        issueTimeLocal = "%02d:00".format(ml.issueHourLocal),
        localTz = ml.localTz,
        modelVersion = forecasts.modelVersion("t1") ?: "unavailable",
        llmModel = llmModel,
    )

    @GetMapping("/turbines")
    fun getTurbines(): List<Turbine> = mock.turbines

    @GetMapping("/forecast")
    fun getForecast(
        @RequestParam turbineId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(defaultValue = "48") horizonHours: Int,
    ): ForecastResponse = forecasts.forecast(turbineId, date, validHorizon(horizonHours))

    @GetMapping("/forecast/revisions")
    fun getForecastRevisions(
        @RequestParam turbineId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): ForecastRevisionsResponse = forecasts.revisions(turbineId, date)

    @GetMapping("/metrics")
    fun getMetrics(
        @RequestParam(required = false) turbineId: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): MetricsResponse {
        if (from.isAfter(to)) throw BadRequestException("'from' must not be after 'to'")
        if (ChronoUnit.DAYS.between(from, to) > MAX_METRICS_DAYS) {
            throw BadRequestException("Period must not exceed $MAX_METRICS_DAYS days")
        }
        return mock.metrics(turbineId, from, to)
    }

    @GetMapping("/agent-log")
    fun getAgentLog(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(required = false) turbineId: String?,
        @RequestParam(defaultValue = "1") revision: Int,
    ): AgentLogResponse = mock.agentLog(date, turbineId, validRevision(revision))

    @PostMapping("/forecast/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun runForecastCycle(@RequestBody request: RunForecastRequest): RunForecastResponse =
        mock.runCycle(request.turbineId, request.date, validHorizon(request.horizonHours ?: 48))

    private fun validHorizon(h: Int): Int =
        if (h == 24 || h == 48) h else throw BadRequestException("horizonHours must be 24 or 48, got $h")

    private fun validRevision(r: Int): Int =
        if (r in 1..MockDataService.REVISIONS_PER_DAY) r
        else throw BadRequestException("revision must be in 1..${MockDataService.REVISIONS_PER_DAY}, got $r")

    companion object {
        const val MAX_METRICS_DAYS = 366
    }
}
