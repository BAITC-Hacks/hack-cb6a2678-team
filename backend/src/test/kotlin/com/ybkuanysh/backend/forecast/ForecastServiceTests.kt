package com.ybkuanysh.backend.forecast

import com.ybkuanysh.backend.dto.AlertSeverity
import com.ybkuanysh.backend.dto.AlertType
import com.ybkuanysh.backend.ml.MlForecastResponse
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.ml.MlUnavailableException
import com.ybkuanysh.backend.mock.MockDataService
import com.ybkuanysh.backend.support.FixtureMl
import com.ybkuanysh.backend.weather.LeakageException
import com.ybkuanysh.backend.weather.WeatherProperties
import com.ybkuanysh.backend.weather.WeatherRunSource
import com.ybkuanysh.backend.weather.WeatherService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastServiceTests {

    @TempDir
    lateinit var cacheDir: Path

    private val mapper = JsonMapper.builder().build()

    /** Погода бэкенда: ветер 7 м/с и −3 °C во всех часах любого прогона. */
    private val weatherSource = WeatherRunSource { _, _, _, run ->
        val times = (0 until 168).map { "\"${TIME.format(run.plus(Duration.ofHours(it.toLong())))}\"" }
        """{"latitude":43.62,"longitude":78.48,"elevation":555.0,
            "hourly":{"time":$times,"wind_speed_100m":${List(168) { 7.0 }},"temperature_2m":${List(168) { -3.0 }}}}"""
    }

    private fun service(ml: FixtureMl = FixtureMl(), offlineWeather: Boolean = false): ForecastService {
        val weather = WeatherService(weatherSource, WeatherProperties(cacheDir = cacheDir.toString(), offline = offlineWeather), mapper)
        return ForecastService(ml, MlProperties(), weather, MockDataService(Clock.systemUTC(), weather))
    }

    private val jan31: LocalDate = LocalDate.parse("2026-01-31")

    @Test
    fun `maps ML v1 response computing UTC times and strict weather bound`() {
        val fc = service().forecast("t1", jan31, 48)
        assertEquals(Instant.parse("2026-01-31T07:00:00Z"), fc.forecastIssuedAt)
        assertEquals(Instant.parse("2026-01-30T18:00:00Z"), fc.weatherIssuedAt)
        assertEquals(48, fc.points.size)
        assertEquals(Instant.parse("2026-01-31T19:00:00Z"), fc.points.first().timestamp)
        assertTrue(fc.points.zipWithNext().all { (a, b) -> Duration.between(a.timestamp, b.timestamp) == Duration.ofHours(1) })
        assertTrue(fc.points.all { it.actualPower == null && it.p10!! <= it.predictedPower && it.predictedPower <= it.p90!! })
        // Ветра и температуры в ML v1 нет — берутся из погоды бэкенда, известной на момент выпуска
        assertTrue(fc.points.all { it.windSpeed == 7.0 && it.temperature == -3.0 })
    }

    @Test
    fun `24h horizon keeps only the next day and summary matches points`() {
        val fc = service().forecast("t2", jan31, 24)
        assertEquals(24, fc.points.size)
        assertEquals(Instant.parse("2026-02-01T18:00:00Z"), fc.points.last().timestamp)
        assertEquals(fc.points.map { it.predictedPower }.average(), fc.summary.meanPower, 0.001)
    }

    @Test
    fun `ML analysis issues become alerts over the whole horizon`() {
        val fc = service().forecast("t1", jan31, 48)
        val nwp = fc.alerts.single { it.message.contains("разбросом ветра") }
        assertEquals(AlertType.low_confidence, nwp.type)
        assertEquals(AlertSeverity.warning, nwp.severity)
        assertEquals(fc.points.first().timestamp, nwp.from)
        assertEquals(fc.points.last().timestamp, nwp.to)
    }

    @Test
    fun `ML v2 fields take precedence`() {
        val v2 = FixtureMl { r: MlForecastResponse ->
            r.copy(
                issuedAtUtc = "2026-01-31T06:00:00Z",
                weatherIssuedBeforeUtc = "2026-01-30T12:00:00Z",
                modelVersion = "lgbm-test",
                hourly = r.hourly.mapIndexed { i, h ->
                    h.copy(timeUtc = Instant.parse("2026-01-31T18:00:00Z").plusSeconds(3600L * i).toString(), windSpeed100m = 11.0, temperature2m = 1.5)
                },
            )
        }
        val fc = service(v2).forecast("t1", jan31, 48)
        assertEquals(Instant.parse("2026-01-31T06:00:00Z"), fc.forecastIssuedAt)
        assertEquals(Instant.parse("2026-01-30T12:00:00Z"), fc.weatherIssuedAt)
        assertEquals("lgbm-test", fc.modelVersion)
        assertEquals(Instant.parse("2026-01-31T18:00:00Z"), fc.points.first().timestamp)
        assertTrue(fc.points.all { it.windSpeed == 11.0 && it.temperature == 1.5 })
    }

    @Test
    fun `refuses forecast built on weather from the future`() {
        val leaky = FixtureMl { it.copy(weatherIssuedBeforeUtc = "2026-01-31T08:00:00Z") }
        assertFailsWith<LeakageException> { service(leaky).forecast("t1", jan31, 48) }
    }

    @Test
    fun `without backend weather points keep null wind`() {
        val fc = service(offlineWeather = true).forecast("t1", jan31, 48)
        assertTrue(fc.points.all { it.windSpeed == null && it.temperature == null })
    }

    @Test
    fun `revisions of a target day come from the two previous issues`() {
        val r = service().revisions("t1", LocalDate.parse("2026-02-05"))
        assertEquals(listOf(1, 2), r.revisions.map { it.revision })
        assertEquals(Instant.parse("2026-02-03T07:00:00Z"), r.revisions[0].forecastIssuedAt)
        assertEquals(Instant.parse("2026-02-04T07:00:00Z"), r.revisions[1].forecastIssuedAt)
        assertNull(r.revisions[0].changeVsPreviousPct)

        // Для суток 04.02 выпуска 02.02 (D+2) в фикстурах нет — остаётся только версия из выпуска 03.02
        val partial = service().revisions("t1", LocalDate.parse("2026-02-04"))
        assertEquals(listOf(2), partial.revisions.map { it.revision })
        assertNull(partial.revisions.single().changeVsPreviousPct)
    }

    @Test
    fun `missing ML data is a 503-style error`() {
        assertFailsWith<MlUnavailableException> { service().forecast("t1", LocalDate.parse("2026-03-05"), 48) }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
    }
}
