package com.ybkuanysh.backend.weather

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeatherServiceTests {

    @TempDir
    lateinit var cacheDir: Path

    private val mapper = JsonMapper.builder().build()
    private val fetched = mutableListOf<Instant>()

    /** Синтетический прогон на [hours] часов; [unavailable] — прогоны, на которые «API» отвечает ошибкой. */
    private fun fakeSource(
        unavailable: Set<Instant> = emptySet(),
        hoursFor: (Instant) -> Int = { 168 },
    ) = WeatherRunSource { _, _, _, run ->
        fetched += run
        if (run in unavailable) throw WeatherUnavailableException("Open-Meteo 400: run not available")
        val hours = hoursFor(run)
        val times = (0 until hours).map { "\"${TIME.format(run.plus(Duration.ofHours(it.toLong())))}\"" }
        val values = (0 until hours).map { (it % 10).toDouble() }
        """
        {"latitude":43.62,"longitude":78.48,"elevation":555.0,
         "hourly":{"time":$times,"wind_speed_100m":$values,"temperature_2m":$values}}
        """.trimIndent()
    }

    private fun service(source: WeatherRunSource, offline: Boolean = false, cache: Path = cacheDir) =
        WeatherService(source, WeatherProperties(cacheDir = cache.toString(), offline = offline), mapper)

    @Test
    fun `latest available run respects publication delay`() {
        val s = service(fakeSource())
        assertEquals(t("2026-01-31T12:00"), s.latestAvailableRun(t("2026-02-01T00:00")))
        assertEquals(t("2026-01-31T18:00"), s.latestAvailableRun(t("2026-02-01T06:59")))
        assertEquals(t("2026-02-01T00:00"), s.latestAvailableRun(t("2026-02-01T07:00")))
    }

    @Test
    fun `parses real Open-Meteo response`() {
        val raw = javaClass.getResource("/weather/ecmwf_ifs_2026-01-31T12.json")!!.readText()
        val run = parseOpenMeteoRun(mapper, raw, "ecmwf_ifs", t("2026-01-31T12:00"))
        assertEquals(6, run.hours.size)
        assertEquals(t("2026-01-31T12:00"), run.hours[0].timestamp)
        assertEquals(7.45, run.hours[0].windSpeed100m)
        assertEquals(43.620384, run.gridLat)
        assertTrue(run.hours.all { it.temperature2m != null && it.surfacePressure != null })
    }

    @Test
    fun `returns only hours after forecast moment and caches the run`() {
        val s = service(fakeSource())
        val at = t("2026-02-01T00:00")
        val fc = s.forecastAt(43.645150, 78.535604, at, 48)
        assertEquals(t("2026-01-31T12:00"), fc.runInitAt)
        assertEquals(48, fc.hours.size)
        assertEquals(t("2026-02-01T01:00"), fc.hours.first().timestamp)
        assertEquals(t("2026-02-03T00:00"), fc.hours.last().timestamp)
        assertEquals(WeatherDataSource.api, fc.source)

        assertEquals(WeatherDataSource.cache, s.forecastAt(43.645150, 78.535604, at, 48).source)
        assertEquals(1, fetched.size)
        assertTrue(Files.exists(cacheDir.resolve("ecmwf_ifs/43.6452_78.5356/2026-01-31T12.json")))
    }

    @Test
    fun `falls back to older run when latest is unavailable or incomplete`() {
        val s = service(fakeSource(unavailable = setOf(t("2026-01-31T12:00"))))
        val fc = s.forecastAt(43.6, 78.5, t("2026-02-01T00:00"), 48)
        assertEquals(t("2026-01-31T06:00"), fc.runInitAt)
        assertEquals(listOf(t("2026-01-31T12:00")), fc.skippedRuns.map { it.runInitAt })

        // Прогон 12 UTC на 50 ч не покрывает 48 ч после 00:00 (нужно 60 ч) — берётся 06 UTC
        val latestShort = fakeSource { if (it == t("2026-01-31T12:00")) 50 else 168 }
        val short = service(latestShort, cache = cacheDir.resolve("short")).forecastAt(43.6, 78.5, t("2026-02-01T00:00"), 48)
        assertEquals(t("2026-01-31T06:00"), short.runInitAt)
        assertEquals(1, short.skippedRuns.size)
    }

    @Test
    fun `fails when no run is usable`() {
        val runs = setOf(t("2026-01-31T12:00"), t("2026-01-31T06:00"), t("2026-01-31T00:00"))
        assertFailsWith<WeatherUnavailableException> {
            service(fakeSource(unavailable = runs)).forecastAt(43.6, 78.5, t("2026-02-01T00:00"), 48)
        }
    }

    @Test
    fun `offline mode never calls the API`() {
        assertFailsWith<WeatherUnavailableException> {
            service(fakeSource(), offline = true).forecastAt(43.6, 78.5, t("2026-02-01T00:00"), 24)
        }
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `never uses data published after the forecast moment`() {
        val s = service(fakeSource())
        var at = t("2026-01-31T00:00")
        while (at.isBefore(t("2026-03-01T00:00"))) {
            val fc = s.forecastAt(43.6, 78.5, at, 48)
            assertTrue(!fc.runAvailableAt.isAfter(at), "run published after $at")
            assertTrue(fc.hours.all { it.timestamp.isAfter(at) }, "hour not after $at")
            at = at.plus(Duration.ofHours(5)) // шаг не кратен 6 ч — проверяем и «неровные» моменты
        }
    }

    private fun t(s: String): Instant = Instant.parse("$s:00Z")

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
    }
}
