package com.ybkuanysh.backend.weather

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("weather")
data class WeatherProperties(
    /** Open-Meteo Single Runs API: прогноз конкретного прогона модели по времени инициализации. */
    val baseUrl: String = "https://single-runs-api.open-meteo.com",
    /** ECMWF IFS HRES 9 км — единственная модель с архивом прогонов на февраль 2026 (с 14.03.2024). */
    val model: String = "ecmwf_ifs",
    /** Прогоны инициализируются каждые N часов, начиная с 00 UTC. */
    val runIntervalHours: Int = 6,
    /**
     * Через сколько после инициализации прогон считается опубликованным. Open-Meteo: «обычно 4–6 ч для
     * глобальных моделей» — берём с запасом, чтобы не использовать ещё не вышедший прогон.
     */
    val publicationDelay: Duration = Duration.ofHours(7),
    /** Сколько более старых прогонов пробовать, если нужный недоступен или неполный. */
    val maxFallbackRuns: Int = 2,
    /**
     * Кэш сырых ответов Open-Meteo; коммитится в репозиторий для офлайн-воспроизводимости.
     * Строка, а не Path: Spring конвертирует Path как ресурс веб-приложения и отвергает «../».
     */
    val cacheDir: String = "../data/weather-cache",
    /** true — только кэш, без сети (как у жюри без интернета). */
    val offline: Boolean = false,
) {
    val cachePath: Path get() = Path.of(cacheDir)
}
