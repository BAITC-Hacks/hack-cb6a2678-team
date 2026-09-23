package com.ybkuanysh.backend.ml

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/** Источник прогнозов ML. Интерфейс — чтобы тесты работали без Python-сервиса. */
interface MlForecastSource {
    fun forecast(site: String, issueDate: LocalDate): MlForecastResponse
    fun model(site: String): MlModelInfo
}

@Component
class MlClient(
    builder: RestClient.Builder,
    props: MlProperties,
    private val mapper: JsonMapper,
) : MlForecastSource {

    private val http = builder.baseUrl(props.baseUrl).build()

    override fun forecast(site: String, issueDate: LocalDate): MlForecastResponse = call("forecast $site $issueDate") {
        http.post().uri("/v1/forecast")
            .body(MlForecastRequest(site, issueDate.toString()))
            .retrieve()
            .body(MlForecastResponse::class.java)
    }

    override fun model(site: String): MlModelInfo = call("model $site") {
        http.get().uri("/v1/models/{site}", site).retrieve().body(MlModelInfo::class.java)
    }

    private fun <T : Any> call(what: String, block: () -> T?): T {
        try {
            return block() ?: throw MlUnavailableException("ML: пустой ответ ($what)")
        } catch (e: RestClientResponseException) {
            // FastAPI: {"detail": "..."} для 503 и {"detail": [...]} для 422
            val detail = runCatching { mapper.readTree(e.responseBodyAsString)["detail"].toString() }.getOrNull()
            throw MlUnavailableException("ML ${e.statusCode.value()} ($what): ${detail ?: e.message}")
        } catch (e: RestClientException) {
            throw MlUnavailableException("ML-сервис недоступен ($what): ${e.message}")
        }
    }
}
