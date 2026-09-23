package com.ybkuanysh.backend.metrics

import com.ybkuanysh.backend.api.BadRequestException
import com.ybkuanysh.backend.ml.MlEvaluationResponse
import com.ybkuanysh.backend.ml.MlEvaluationRow
import com.ybkuanysh.backend.ml.MlForecastSource
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.support.FixtureMl
import com.ybkuanysh.backend.weather.WeatherProperties
import com.ybkuanysh.backend.weather.WeatherRunSource
import com.ybkuanysh.backend.weather.WeatherService
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Эталонные значения посчитаны независимо в pandas по тем же фикстурам (01–03.01.2026). */
class MetricsServiceTests {

    private val props = MlProperties(evaluationDir = "src/test/resources/evaluation", scadaDir = "src/test/resources/scada")

    private fun service(ml: MlForecastSource = FixtureMl()): MetricsService {
        val weather = WeatherService(WeatherRunSource { _, _, _, _ -> error("no network") }, WeatherProperties(offline = true), JsonMapper.builder().build())
        return MetricsService(EvaluationRepository(ml, props), ScadaRepository(props), props, TurbineRegistry())
    }

    private val jan1 = LocalDate.parse("2026-01-01")
    private val jan3 = LocalDate.parse("2026-01-03")

    @Test
    fun `metrics for one turbine match pandas reference`() {
        val m = service().metrics("t1", jan1, jan3)
        assertEquals(72, m.sampleHours)
        assertEquals(0.212, m.mae)
        assertEquals(0.265, m.rmse)
        assertEquals(80.6, m.mape)
        assertEquals(0.013, m.bias)
        assertEquals(0.251, m.baselineMae)
        assertEquals(0.276, m.powerCurveBaselineMae)
        assertEquals(0.778, m.intervalCoverage)
        assertEquals(listOf(0.228, 0.170, 0.237), m.byDay.map { it.mae })
        assertEquals(48, m.byLeadTime.size)
        assertEquals(0.267, m.byLeadTime.first().mae)
        assertEquals(0.067, m.byLeadTime.last().mae)
        assertTrue(m.evaluationSource!!.startsWith("csv:"))
    }

    @Test
    fun `without turbine both are combined and period is known`() {
        val s = service()
        assertEquals(144, s.metrics(null, jan1, jan3).sampleHours)
        assertEquals(jan1 to jan3, s.period())
        assertEquals(24, s.metrics("t2", jan1, jan1).sampleHours)
    }

    @Test
    fun `period without evaluation data is a bad request`() {
        assertFailsWith<BadRequestException> { service().metrics("t1", LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28")) }
    }

    @Test
    fun `evaluation from ML v2 endpoint takes precedence over CSV`() {
        val v2 = object : MlForecastSource by FixtureMl() {
            override fun evaluation(site: String) = MlEvaluationResponse(
                site = site,
                modelVersion = "lgbm-test",
                rows = listOf(
                    // 01.01 00:00 местного (UTC+5) = 31.12 19:00 UTC
                    MlEvaluationRow("2025-12-31T19:00:00Z", 1, forecast = 0.5, p10 = 0.2, p90 = 0.8, actual = 0.3, pcBaseline = 0.6),
                    MlEvaluationRow("2025-12-31T20:00:00Z", 1, forecast = 0.4, p10 = 0.1, p90 = 0.35, actual = 0.4, pcBaseline = 0.4),
                ),
            )
        }
        val m = service(v2).metrics("t1", jan1, jan1)
        assertEquals("ml:/v1/evaluation", m.evaluationSource)
        assertEquals("lgbm-test", m.evaluationModelVersion)
        assertEquals(2, m.sampleHours)
        assertEquals(0.1, m.mae)
        assertEquals(0.5, m.intervalCoverage)
        assertEquals(listOf(1, 2), m.byLeadTime.map { it.leadHour })
    }
}
