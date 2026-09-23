package com.ybkuanysh.backend.weather

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Прогноз погоды «как он был известен» в момент прогноза.
 *
 * Берёт последний прогон, опубликованный не позже этого момента, и отдаёт только часы после него.
 * Если прогон недоступен или в нём не хватает часов — пробует более старые (до maxFallbackRuns).
 * Сырые ответы кэшируются на диск, чтобы backtest воспроизводился без сети.
 */
@Service
class WeatherService(
    private val source: WeatherRunSource,
    private val props: WeatherProperties,
    private val mapper: JsonMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Последний прогон, который к моменту [at] уже опубликован. */
    fun latestAvailableRun(at: Instant): Instant {
        val interval = props.runIntervalHours * 3600L
        val latestPublishedInit = at.minus(props.publicationDelay).epochSecond
        return Instant.ofEpochSecond(Math.floorDiv(latestPublishedInit, interval) * interval)
    }

    fun forecastAt(lat: Double, lon: Double, at: Instant, horizonHours: Int): WeatherForecast {
        require(horizonHours in 1..168) { "horizonHours must be in 1..168, got $horizonHours" }
        val skipped = mutableListOf<SkippedRun>()
        var runInitAt = latestAvailableRun(at)
        repeat(props.maxFallbackRuns + 1) {
            val availableAt = runInitAt.plus(props.publicationDelay)
            if (availableAt.isAfter(at)) {
                throw LeakageException("Прогон $runInitAt публикуется в $availableAt — позже момента прогноза $at")
            }
            try {
                val (raw, dataSource) = loadRaw(lat, lon, runInitAt)
                val run = parseOpenMeteoRun(mapper, raw, props.model, runInitAt)
                val end = at.plus(Duration.ofHours(horizonHours.toLong()))
                val hours = run.hours.filter { it.timestamp.isAfter(at) && !it.timestamp.isAfter(end) }
                val incomplete = hours.size < horizonHours || hours.any { it.windSpeed100m == null || it.temperature2m == null }
                if (!incomplete) {
                    return WeatherForecast(
                        model = props.model,
                        forecastAt = at,
                        runInitAt = runInitAt,
                        runAvailableAt = availableAt,
                        gridLat = run.gridLat,
                        gridLon = run.gridLon,
                        elevation = run.elevation,
                        source = dataSource,
                        skippedRuns = skipped.toList(),
                        hours = hours,
                    )
                }
                skipped += SkippedRun(runInitAt, "в прогоне нет полных данных на $horizonHours ч после $at")
            } catch (e: WeatherUnavailableException) {
                skipped += SkippedRun(runInitAt, e.message ?: "недоступен")
            }
            log.warn("Weather run {} skipped: {}", runInitAt, skipped.last().reason)
            runInitAt = runInitAt.minus(Duration.ofHours(props.runIntervalHours.toLong()))
        }
        throw WeatherUnavailableException(
            "Нет доступного прогона погоды для $at: " + skipped.joinToString("; ") { "${it.runInitAt} — ${it.reason}" },
        )
    }

    private fun loadRaw(lat: Double, lon: Double, runInitAt: Instant): Pair<String, WeatherDataSource> {
        val file = cacheFile(lat, lon, runInitAt)
        if (Files.exists(file)) return Files.readString(file) to WeatherDataSource.cache
        if (props.offline) throw WeatherUnavailableException("нет в кэше ($file), включён offline-режим")
        val raw = source.fetchRaw(lat, lon, props.model, runInitAt)
        // Проверяем до записи, чтобы в кэш не попали ошибки
        parseOpenMeteoRun(mapper, raw, props.model, runInitAt)
        Files.createDirectories(file.parent)
        Files.writeString(file, raw)
        return raw to WeatherDataSource.api
    }

    private fun cacheFile(lat: Double, lon: Double, runInitAt: Instant): Path =
        props.cachePath
            .resolve(props.model)
            .resolve(String.format(Locale.ROOT, "%.4f_%.4f", lat, lon))
            .resolve(CACHE_NAME.format(runInitAt) + ".json")

    companion object {
        private val CACHE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC)
    }
}
