package com.ybkuanysh.backend.weather

import com.ybkuanysh.backend.api.BadRequestException
import com.ybkuanysh.backend.dto.WeatherResponse
import com.ybkuanysh.backend.turbine.TurbineRegistry
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/weather")
class WeatherController(private val weather: WeatherService, private val turbines: TurbineRegistry) {

    @GetMapping
    fun getWeather(
        @RequestParam turbineId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) at: Instant,
        @RequestParam(defaultValue = "48") horizonHours: Int,
    ): WeatherResponse {
        if (horizonHours !in 1..72) throw BadRequestException("horizonHours must be in 1..72, got $horizonHours")
        val turbine = turbines.requireTurbine(turbineId)
        return WeatherResponse(turbineId, weather.forecastAt(turbine.lat, turbine.lon, at, horizonHours))
    }
}
