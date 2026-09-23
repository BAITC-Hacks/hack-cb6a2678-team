package com.ybkuanysh.backend.api

import com.ybkuanysh.backend.dto.AgentLogResponse
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.RunForecastRequest
import com.ybkuanysh.backend.dto.RunForecastResponse
import com.ybkuanysh.backend.dto.Turbine
import com.ybkuanysh.backend.mock.MockDataService
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
class WesController(private val mock: MockDataService) {

    @GetMapping("/turbines")
    fun getTurbines(): List<Turbine> = mock.turbines

    @GetMapping("/forecast")
    fun getForecast(
        @RequestParam turbineId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(defaultValue = "48") horizonHours: Int,
    ): ForecastResponse = mock.forecast(turbineId, date, validHorizon(horizonHours))

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
    ): AgentLogResponse = mock.agentLog(date, turbineId)

    @PostMapping("/forecast/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun runForecastCycle(@RequestBody request: RunForecastRequest): RunForecastResponse =
        mock.runCycle(request.turbineId, request.date, validHorizon(request.horizonHours ?: 48))

    private fun validHorizon(h: Int): Int =
        if (h == 24 || h == 48) h else throw BadRequestException("horizonHours must be 24 or 48, got $h")

    companion object {
        const val MAX_METRICS_DAYS = 366
    }
}
