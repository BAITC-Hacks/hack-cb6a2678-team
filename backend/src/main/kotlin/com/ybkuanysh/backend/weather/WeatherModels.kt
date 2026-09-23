package com.ybkuanysh.backend.weather

import java.time.Instant

/** Один час прогноза погоды. Имена полей совпадают с contracts/ml-service.openapi.yaml (WeatherHour). */
data class WeatherHour(
    val timestamp: Instant,
    val windSpeed10m: Double?,
    val windSpeed80m: Double?,
    val windSpeed100m: Double?,
    val windSpeed120m: Double?,
    val windDirection100m: Double?,
    val windGusts10m: Double?,
    val temperature2m: Double?,
    val relativeHumidity2m: Double?,
    val surfacePressure: Double?,
)

/** Полный прогон модели погоды для точки. */
data class WeatherRun(
    val model: String,
    val runInitAt: Instant,
    val gridLat: Double,
    val gridLon: Double,
    val elevation: Double,
    val hours: List<WeatherHour>,
)

enum class WeatherDataSource { cache, api }

/** Прогноз погоды, доступный в момент [forecastAt], на часы (forecastAt, forecastAt + horizon]. */
data class WeatherForecast(
    val model: String,
    val forecastAt: Instant,
    val runInitAt: Instant,
    val runAvailableAt: Instant,
    val gridLat: Double,
    val gridLon: Double,
    val elevation: Double,
    val source: WeatherDataSource,
    /** Прогоны, которые пришлось пропустить (недоступны или неполные) — для журнала агента. */
    val skippedRuns: List<SkippedRun>,
    val hours: List<WeatherHour>,
)

data class SkippedRun(val runInitAt: Instant, val reason: String)

class WeatherUnavailableException(message: String) : RuntimeException(message)

/** Попытка использовать данные, опубликованные позже момента прогноза. Это баг, а не ситуация для ретрая. */
class LeakageException(message: String) : IllegalStateException(message)
