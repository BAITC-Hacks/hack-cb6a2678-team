package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentToolCall
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.metrics.EvaluationRepository
import com.ybkuanysh.backend.metrics.MetricsService
import com.ybkuanysh.backend.metrics.ScadaRepository
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.support.FixtureMl
import com.ybkuanysh.backend.weather.WeatherHour
import com.ybkuanysh.backend.weather.WeatherProperties
import com.ybkuanysh.backend.weather.WeatherRunSource
import com.ybkuanysh.backend.weather.WeatherService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.model.ToolContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentWeatherToolTests {

    @TempDir
    lateinit var cacheDir: Path

    /** Ветер растёт на 1 м/с каждый час от 0, температура 0 °C, влажность 95 %. */
    private val source = WeatherRunSource { _, _, _, run ->
        val times = (0 until 168).map { "\"${TIME.format(run.plus(Duration.ofHours(it.toLong())))}\"" }
        val hours = (0 until 168)
        """
        {"latitude":43.62,"longitude":78.48,"elevation":555.0,
         "hourly":{"time":$times,
                   "wind_speed_100m":${hours.map { (it % 30).toDouble() }},
                   "temperature_2m":${hours.map { 0.0 }},
                   "relative_humidity_2m":${hours.map { 95.0 }}}}
        """.trimIndent()
    }

    private fun tools(): AgentTools {
        val mapper = JsonMapper.builder().build()
        val weather = WeatherService(source, WeatherProperties(cacheDir = cacheDir.toString()), mapper)
        val mock = TurbineRegistry()
        // localTz = UTC: дни и часы в проверках ниже — в UTC
        val ml = MlProperties(localTz = "Z")
        val metrics = MetricsService(EvaluationRepository(FixtureMl(), ml), ScadaRepository(ml), ml, mock)
        return AgentTools(mock, weather, ForecastService(FixtureMl(), ml, weather, mock), metrics, ml)
    }

    private fun ctx() = mutableListOf<AgentToolCall>().let { it to ToolContext(mapOf(AgentTools.TRACE_KEY to it)) }

    @Test
    fun `weather tool returns one calendar day as known at its start`() {
        val (trace, ctx) = ctx()
        val view = tools().getWeather("t1", "2026-02-01", null, true, ctx)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), view.forecastAt)
        assertEquals(Instant.parse("2026-01-31T12:00:00Z"), view.runInitAt)
        assertTrue(!view.runPublishedAt.isAfter(view.forecastAt))
        assertEquals(24, view.summary.hours)
        assertEquals(24, view.hourly!!.size)
        assertTrue(view.hourly!!.first().startsWith("01.02 01:00 | "))
        // Один день — одна строка вывода, без итога за период
        assertEquals(1, view.conclusions.size)
        assertTrue(view.conclusions.single().startsWith("За 01.02.2026: "))
        assertTrue("РИСК ОБЛЕДЕНЕНИЯ 24 ч" in view.conclusions.single())
        assertEquals(listOf("getWeather"), trace.map { it.tool })
        assertTrue(trace.single().ok)
    }

    @Test
    fun `weather tool covers a range of days and validates input`() {
        val (trace, ctx) = ctx()
        val view = tools().getWeather("t2", "2026-02-10", "2026-02-12", null, ctx)
        assertEquals(null, view.hourly)
        assertEquals(72, view.summary.hours)
        assertEquals(
            listOf("За 10.02.2026", "За 11.02.2026", "За 12.02.2026"),
            view.conclusions.take(3).map { it.substringBefore(":") },
        )
        assertTrue(view.conclusions.drop(3).all { it.startsWith("За весь период 10.02.2026–12.02.2026: ") })

        assertFailsWith<IllegalArgumentException> { tools().getWeather("t1", "5 февраля", null, null, ctx) }
        assertFailsWith<IllegalArgumentException> { tools().getWeather("t1", "2026-02-01", "2026-02-10", null, ctx) }
        assertFalse(trace.last().ok)
    }

    @Test
    fun `summary counts calm, storm and icing hours`() {
        fun h(i: Int, wind: Double, t: Double, rh: Double) = WeatherHour(
            Instant.parse("2026-02-01T00:00:00Z").plusSeconds(3600L * i),
            null, null, wind, null, null, null, t, rh, null,
        )
        val s = summarize(listOf(h(1, 2.0, -3.0, 95.0), h(2, 10.0, 5.0, 95.0), h(3, 24.0, -10.0, 50.0)))
        assertEquals(1, s.calmHours)
        assertEquals(1, s.stormHours)
        assertEquals(1, s.icingRiskHours)
        assertEquals(24.0, s.maxWind100m)
        assertEquals(12.0, s.meanWind100m)
        assertEquals(-10.0, s.minTemperature)

        val calm = summarize(listOf(h(1, 5.0, 10.0, 40.0)))
        assertTrue("Штормового отключения не ожидается: ветер на 100 м не достигает 23 м/с." in conclusions(calm))
        assertTrue("Риска обледенения нет." in conclusions(calm))
        assertTrue(conclusions(s).any { it.startsWith("РИСК ШТОРМОВОГО ОТКЛЮЧЕНИЯ: 1 ч") })
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
    }
}
