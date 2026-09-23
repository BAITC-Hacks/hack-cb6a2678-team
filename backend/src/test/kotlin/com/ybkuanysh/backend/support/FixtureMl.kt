package com.ybkuanysh.backend.support

import com.ybkuanysh.backend.ml.MlForecastResponse
import com.ybkuanysh.backend.ml.MlForecastSource
import com.ybkuanysh.backend.ml.MlModelInfo
import com.ybkuanysh.backend.ml.MlUnavailableException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.LocalDate

/**
 * ML-сервис из сохранённых настоящих ответов `src/test/resources/ml/forecast_<site>_<date>.json`.
 * Есть выпуски 2026-01-31, 2026-02-03, 2026-02-04 для обеих турбин; остальные даты → 503, как у ML без погоды.
 */
class FixtureMl(private val transform: (MlForecastResponse) -> MlForecastResponse = { it }) : MlForecastSource {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    override fun forecast(site: String, issueDate: LocalDate): MlForecastResponse {
        val raw = javaClass.getResource("/ml/forecast_${site}_$issueDate.json")?.readText()
            ?: throw MlUnavailableException("ML 503: no fixture for $site $issueDate")
        return transform(mapper.readValue(raw, MlForecastResponse::class.java))
    }

    override fun model(site: String) = MlModelInfo(site = site, version = "1.0.0", trainedAt = "2026-09-23T10:57:06+00:00")
}

@TestConfiguration
class FixtureMlConfig {
    @Bean
    @Primary
    fun fixtureMl(): MlForecastSource = FixtureMl()
}
