package com.ybkuanysh.backend.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class WesControllerTests(@Autowired val mvc: MockMvc) {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `turbines returns list with coordinates`() {
        mvc.get("/api/turbines").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].id") { value("t1") }
            jsonPath("$[0].lat") { value(43.645150) }
        }
    }

    @Test
    fun `forecast defaults to 48 points with actuals in backtest period`() {
        mvc.get("/api/forecast?turbineId=t1&date=2026-02-01").andExpect {
            status { isOk() }
            jsonPath("$.turbineId") { value("t1") }
            jsonPath("$.forecastIssuedAt") { value("2026-02-01T00:00:00Z") }
            jsonPath("$.horizonHours") { value(48) }
            jsonPath("$.points.length()") { value(48) }
            jsonPath("$.points[0].timestamp") { value("2026-02-01T01:00:00Z") }
            jsonPath("$.points[0].actualPower") { isNumber() }
        }
    }

    @Test
    fun `forecast is deterministic`() {
        val url = "/api/forecast?turbineId=t2&date=2026-02-10&horizonHours=24"
        val a = mvc.get(url).andReturn().response.contentAsString
        val b = mvc.get(url).andReturn().response.contentAsString
        kotlin.test.assertEquals(a, b)
    }

    @Test
    fun `forecast after backtest period has null actuals`() {
        mvc.get("/api/forecast?turbineId=t1&date=2026-03-05&horizonHours=24").andExpect {
            status { isOk() }
            jsonPath("$.points.length()") { value(24) }
            jsonPath("$.points[0].actualPower") { doesNotExist() }
        }
    }

    @Test
    fun `forecast for unknown turbine is 404`() {
        mvc.get("/api/forecast?turbineId=nope&date=2026-02-01").andExpect {
            status { isNotFound() }
            jsonPath("$.message") { isString() }
        }
    }

    @Test
    fun `forecast validates params`() {
        mvc.get("/api/forecast?turbineId=t1&date=2026-02-01&horizonHours=12").andExpect { status { isBadRequest() } }
        mvc.get("/api/forecast?turbineId=t1&date=bad").andExpect { status { isBadRequest() } }
        mvc.get("/api/forecast?turbineId=t1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `metrics for february`() {
        mvc.get("/api/metrics?turbineId=t1&from=2026-02-01&to=2026-02-28").andExpect {
            status { isOk() }
            jsonPath("$.periodFrom") { value("2026-02-01") }
            jsonPath("$.periodTo") { value("2026-02-28") }
            jsonPath("$.mae") { isNumber() }
            jsonPath("$.baselineMae") { isNumber() }
            jsonPath("$.byDay.length()") { value(28) }
        }
        mvc.get("/api/metrics?from=2026-02-01&to=2026-02-03").andExpect {
            status { isOk() }
            jsonPath("$.turbineId") { doesNotExist() }
            jsonPath("$.byDay.length()") { value(3) }
        }
        mvc.get("/api/metrics?from=2026-02-10&to=2026-02-01").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `agent log for scheduled cycle`() {
        mvc.get("/api/agent-log?date=2026-02-01").andExpect {
            status { isOk() }
            jsonPath("$.cycleId") { value("cycle_2026-02-01T00:00:00Z") }
            jsonPath("$.revision") { value(1) }
            jsonPath("$.steps.length()") { value(5) }
            jsonPath("$.steps[0].status") { value("success") }
            jsonPath("$.steps[0].tool") { value("fetchWeather") }
            jsonPath("$.steps[0].dataIssuedAt") { value("2026-01-31T12:00:00Z") }
        }
        mvc.get("/api/agent-log?date=2026-02-01&revision=3").andExpect {
            jsonPath("$.forecastIssuedAt") { value("2026-02-01T12:00:00Z") }
        }
        mvc.get("/api/agent-log?date=2026-02-03").andExpect {
            jsonPath("$.steps[0].status") { value("retrying") }
        }
    }

    @Test
    fun `manual run is accepted and shows up in agent log`() {
        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"t2","date":"2026-02-15"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("processing") }
            jsonPath("$.cycleId") { isString() }
        }
        mvc.get("/api/agent-log?date=2026-02-15&turbineId=t2").andExpect {
            status { isOk() }
            jsonPath("$.steps[0].status") { value("running") }
        }
        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"t1"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `meta exposes backtest period`() {
        mvc.get("/api/meta").andExpect {
            status { isOk() }
            jsonPath("$.backtestFrom") { value("2026-01-31") }
            jsonPath("$.backtestTo") { value("2026-02-27") }
            jsonPath("$.revisionsPerDay") { value(4) }
            jsonPath("$.llmModel") { isString() }
        }
    }

    @Test
    fun `forecast has quantiles, summary and weather issued before forecast`() {
        val body = mvc.get("/api/forecast?turbineId=t1&date=2026-02-05&revision=2").andExpect {
            status { isOk() }
            jsonPath("$.revision") { value(2) }
            jsonPath("$.forecastIssuedAt") { value("2026-02-05T06:00:00Z") }
            jsonPath("$.weatherIssuedAt") { value("2026-02-04T18:00:00Z") }
            jsonPath("$.points[0].timestamp") { value("2026-02-05T07:00:00Z") }
            jsonPath("$.summary.meanPower") { isNumber() }
            jsonPath("$.alerts") { isArray() }
            jsonPath("$.agentReport") { isString() }
        }.andReturn().response.contentAsString
        val json = mapper.readTree(body)
        val points = (0 until json["points"].size()).map { json["points"][it] }
        val mean = points.map { it["predictedPower"].doubleValue() }.average()
        assertEquals(json["summary"]["meanPower"].doubleValue(), mean, 0.001)
        points.forEach {
            assertTrue(it["p10"].doubleValue() <= it["predictedPower"].doubleValue())
            assertTrue(it["predictedPower"].doubleValue() <= it["p90"].doubleValue())
        }
    }

    @Test
    fun `revisions list and validation`() {
        mvc.get("/api/forecast/revisions?turbineId=t1&date=2026-02-05").andExpect {
            status { isOk() }
            jsonPath("$.revisions.length()") { value(4) }
            jsonPath("$.revisions[0].changeVsPreviousPct") { doesNotExist() }
            jsonPath("$.revisions[1].changeVsPreviousPct") { isNumber() }
        }
        mvc.get("/api/forecast?turbineId=t1&date=2026-02-05&revision=5").andExpect { status { isBadRequest() } }
        mvc.get("/api/agent-log?date=2026-02-05&revision=0").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `metrics include baselines, coverage and lead time`() {
        mvc.get("/api/metrics?turbineId=t1&from=2026-01-31&to=2026-02-27").andExpect {
            status { isOk() }
            jsonPath("$.powerCurveBaselineMae") { isNumber() }
            jsonPath("$.intervalCoverage") { isNumber() }
            jsonPath("$.byLeadTime.length()") { value(48) }
            jsonPath("$.byLeadTime[0].leadHour") { value(1) }
        }
    }
}
