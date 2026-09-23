package com.ybkuanysh.backend.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.ybkuanysh.backend.support.FixtureMlConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(FixtureMlConfig::class)
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
    fun `forecast comes from ML issued at noon local for the next two days`() {
        mvc.get("/api/forecast?turbineId=t1&date=2026-01-31").andExpect {
            status { isOk() }
            jsonPath("$.turbineId") { value("t1") }
            // 31.01 12:00 Asia/Almaty (UTC+5) → сутки 01.02 и 02.02 местного времени
            jsonPath("$.forecastIssuedAt") { value("2026-01-31T07:00:00Z") }
            jsonPath("$.weatherIssuedAt") { value("2026-01-30T18:00:00Z") }
            jsonPath("$.horizonHours") { value(48) }
            jsonPath("$.points.length()") { value(48) }
            jsonPath("$.points[0].timestamp") { value("2026-01-31T19:00:00Z") }
            jsonPath("$.points[47].timestamp") { value("2026-02-02T18:00:00Z") }
            jsonPath("$.points[0].actualPower") { doesNotExist() }
            jsonPath("$.weatherSource") { value("open-meteo-previous-runs") }
            jsonPath("$.modelVersion") { value("windml-2026-09-23") }
        }
    }

    @Test
    fun `forecast is deterministic and 24h means the next day only`() {
        val url = "/api/forecast?turbineId=t2&date=2026-02-03&horizonHours=24"
        val a = mvc.get(url).andExpect { jsonPath("$.points.length()") { value(24) } }.andReturn().response.contentAsString
        val b = mvc.get(url).andReturn().response.contentAsString
        assertEquals(a, b)
    }

    @Test
    fun `forecast without ML data is 503`() {
        mvc.get("/api/forecast?turbineId=t1&date=2026-03-05").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.message") { isString() }
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
    fun `metrics on the January holdout`() {
        mvc.get("/api/metrics?turbineId=t1&from=2026-01-01&to=2026-01-03").andExpect {
            status { isOk() }
            jsonPath("$.periodFrom") { value("2026-01-01") }
            jsonPath("$.mae") { value(0.212) }
            jsonPath("$.baselineMae") { value(0.251) }
            jsonPath("$.bias") { value(0.013) }
            jsonPath("$.sampleHours") { value(72) }
            jsonPath("$.byDay.length()") { value(3) }
        }
        mvc.get("/api/metrics?from=2026-01-01&to=2026-01-03").andExpect {
            status { isOk() }
            jsonPath("$.turbineId") { doesNotExist() }
            jsonPath("$.sampleHours") { value(144) }
        }
        // Без дат — весь период теста
        mvc.get("/api/metrics?turbineId=t1").andExpect {
            status { isOk() }
            jsonPath("$.periodFrom") { value("2026-01-01") }
            jsonPath("$.periodTo") { value("2026-01-03") }
        }
        // Факта за февраль нет — метрики за него посчитать нельзя
        mvc.get("/api/metrics?from=2026-02-01&to=2026-02-28").andExpect { status { isBadRequest() } }
        mvc.get("/api/metrics?from=2026-02-10&to=2026-02-01").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `agent cycle runs through all steps and shows up in agent log`() {
        // До запуска цикла журнала нет
        mvc.get("/api/agent-log?date=2026-02-04&turbineId=t2").andExpect { status { isNotFound() } }

        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"t2","date":"2026-02-04"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("processing") }
            jsonPath("$.cycleId") { isString() }
        }
        // В тестах цикл синхронный (cycle.async=false), LLM выключена, погода офлайн
        mvc.get("/api/agent-log?date=2026-02-04&turbineId=t2").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("success") }
            jsonPath("$.turbineId") { value("t2") }
            jsonPath("$.forecastIssuedAt") { value("2026-02-04T07:00:00Z") }
            jsonPath("$.reportSource") { value("template") }
            jsonPath("$.steps.length()") { value(6) }
            jsonPath("$.steps[0].tool") { value("fetchWeather") }
            jsonPath("$.steps[0].status") { value("failed") }
            jsonPath("$.steps[1].tool") { value("predict") }
            jsonPath("$.steps[1].dataIssuedAt") { value("2026-02-03T18:00:00Z") }
            jsonPath("$.steps[2].status") { value("success") }
            jsonPath("$.steps[3].details") { value(org.hamcrest.Matchers.containsString("было")) }
            jsonPath("$.steps[5].tool") { value("saveForecast") }
        }
        // Отчёт цикла попадает в прогноз
        mvc.get("/api/forecast?turbineId=t2&date=2026-02-04").andExpect {
            jsonPath("$.agentReport") { value(org.hamcrest.Matchers.startsWith("За 05.02.2026")) }
        }
    }

    @Test
    fun `agent cycle without ML fails after retries`() {
        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"t1","date":"2026-03-10"}"""
        }.andExpect { status { isAccepted() } }
        mvc.get("/api/agent-log?date=2026-03-10&turbineId=t1").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("failed") }
            jsonPath("$.steps[1].status") { value("retrying") }
            jsonPath("$.steps[2].status") { value("retrying") }
            jsonPath("$.steps[3].status") { value("failed") }
            jsonPath("$.steps.length()") { value(4) }
        }
        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"t1"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.post("/api/forecast/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"turbineId":"nope","date":"2026-02-04"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `meta exposes backtest period`() {
        mvc.get("/api/meta").andExpect {
            status { isOk() }
            jsonPath("$.backtestFrom") { value("2026-01-31") }
            jsonPath("$.backtestTo") { value("2026-02-26") }
            jsonPath("$.revisionsPerDay") { value(2) }
            jsonPath("$.issueTimeLocal") { value("12:00") }
            jsonPath("$.localTz") { value("Asia/Almaty") }
            jsonPath("$.metricsFrom") { value("2026-01-01") }
            jsonPath("$.metricsTo") { value("2026-01-03") }
            jsonPath("$.modelVersion") { value("windml-1.0.0-2026-09-23") }
            jsonPath("$.llmModel") { isString() }
        }
    }

    @Test
    fun `forecast has quantiles, summary and weather issued before forecast`() {
        val body = mvc.get("/api/forecast?turbineId=t1&date=2026-02-04").andExpect {
            status { isOk() }
            jsonPath("$.forecastIssuedAt") { value("2026-02-04T07:00:00Z") }
            jsonPath("$.weatherIssuedAt") { value("2026-02-03T18:00:00Z") }
            jsonPath("$.points[0].timestamp") { value("2026-02-04T19:00:00Z") }
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
            // Сутки 05.02: из выпуска 03.02 (как D+2) и из выпуска 04.02 (как D+1)
            jsonPath("$.revisions.length()") { value(2) }
            jsonPath("$.revisions[0].forecastIssuedAt") { value("2026-02-03T07:00:00Z") }
            jsonPath("$.revisions[1].forecastIssuedAt") { value("2026-02-04T07:00:00Z") }
            jsonPath("$.revisions[0].changeVsPreviousPct") { doesNotExist() }
            jsonPath("$.revisions[1].changeVsPreviousPct") { isNumber() }
        }
    }

    @Test
    fun `metrics include baselines, coverage and lead time`() {
        mvc.get("/api/metrics?turbineId=t1&from=2026-01-01&to=2026-01-03").andExpect {
            status { isOk() }
            jsonPath("$.powerCurveBaselineMae") { value(0.276) }
            jsonPath("$.intervalCoverage") { isNumber() }
            jsonPath("$.byLeadTime.length()") { value(48) }
            jsonPath("$.byLeadTime[0].leadHour") { value(1) }
        }
    }
}
