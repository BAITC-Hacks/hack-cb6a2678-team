package com.ybkuanysh.backend.weather

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Источник сырых прогонов погоды (JSON Open-Meteo). Интерфейс — чтобы тесты работали без сети. */
fun interface WeatherRunSource {
    fun fetchRaw(lat: Double, lon: Double, model: String, runInitAt: Instant): String
}

@Component
class OpenMeteoClient(
    builder: RestClient.Builder,
    private val props: WeatherProperties,
    private val mapper: JsonMapper,
) : WeatherRunSource {

    private val http = builder.baseUrl(props.baseUrl).build()

    override fun fetchRaw(lat: Double, lon: Double, model: String, runInitAt: Instant): String {
        try {
            return http.get()
                .uri {
                    it.path("/v1/forecast")
                        .queryParam("latitude", lat)
                        .queryParam("longitude", lon)
                        .queryParam("models", model)
                        .queryParam("run", RUN_FORMAT.format(runInitAt))
                        .queryParam("hourly", HOURLY_VARIABLES)
                        .queryParam("wind_speed_unit", "ms")
                        .queryParam("timezone", "GMT")
                        .build()
                }
                .retrieve()
                .body(String::class.java)
                ?: throw WeatherUnavailableException("Пустой ответ Open-Meteo")
        } catch (e: RestClientResponseException) {
            // Open-Meteo отвечает 400 {"error":true,"reason":"..."}, например если прогон не в архиве
            val reason = runCatching { mapper.readTree(e.responseBodyAsString)["reason"].stringValue() }.getOrNull()
            throw WeatherUnavailableException("Open-Meteo ${e.statusCode.value()}: ${reason ?: e.message}")
        } catch (e: RestClientException) {
            throw WeatherUnavailableException("Open-Meteo недоступен: ${e.message}")
        }
    }

    companion object {
        val HOURLY_VARIABLES = listOf(
            "wind_speed_10m", "wind_speed_80m", "wind_speed_100m", "wind_speed_120m",
            "wind_direction_100m", "wind_gusts_10m", "temperature_2m", "relative_humidity_2m", "surface_pressure",
        ).joinToString(",")

        private val RUN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
    }
}

/** Разбор ответа Open-Meteo (timezone=GMT, время без зоны) в [WeatherRun]. */
fun parseOpenMeteoRun(mapper: JsonMapper, raw: String, model: String, runInitAt: Instant): WeatherRun {
    val root = mapper.readTree(raw)
    if (root["error"]?.booleanValue() == true) {
        throw WeatherUnavailableException("Open-Meteo: ${root["reason"]?.stringValue()}")
    }
    val hourly = root["hourly"] ?: throw WeatherUnavailableException("В ответе Open-Meteo нет блока hourly")
    val times = hourly["time"]
    fun col(name: String, i: Int): Double? = hourly[name]?.get(i)?.takeUnless { it.isNull }?.doubleValue()
    val hours = (0 until times.size()).map { i ->
        WeatherHour(
            timestamp = LocalDateTime.parse(times[i].stringValue()).toInstant(ZoneOffset.UTC),
            windSpeed10m = col("wind_speed_10m", i),
            windSpeed80m = col("wind_speed_80m", i),
            windSpeed100m = col("wind_speed_100m", i),
            windSpeed120m = col("wind_speed_120m", i),
            windDirection100m = col("wind_direction_100m", i),
            windGusts10m = col("wind_gusts_10m", i),
            temperature2m = col("temperature_2m", i),
            relativeHumidity2m = col("relative_humidity_2m", i),
            surfacePressure = col("surface_pressure", i),
        )
    }
    return WeatherRun(
        model = model,
        runInitAt = runInitAt,
        gridLat = root["latitude"].doubleValue(),
        gridLon = root["longitude"].doubleValue(),
        elevation = root["elevation"].doubleValue(),
        hours = hours,
    )
}
