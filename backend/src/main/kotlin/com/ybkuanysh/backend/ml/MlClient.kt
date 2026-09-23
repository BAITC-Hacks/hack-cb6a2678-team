package com.ybkuanysh.backend.ml

import org.slf4j.LoggerFactory
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

    /** Отложенный тест (`GET /v1/evaluation/{site}`, контракт v2); null — ML пока его не отдаёт. */
    fun evaluation(site: String): MlEvaluationResponse? = null
}

@Component
class MlClient(
    builder: RestClient.Builder,
    props: MlProperties,
    private val mapper: JsonMapper,
) : MlForecastSource {

    private val http = builder.baseUrl(props.baseUrl).build()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun forecast(site: String, issueDate: LocalDate): MlForecastResponse = call("forecast $site $issueDate") {
        http.post().uri("/v1/forecast")
            .body(MlForecastRequest(site, issueDate.toString()))
            .retrieve()
            .body(MlForecastResponse::class.java)
    }

    override fun model(site: String): MlModelInfo = call("model $site") {
        http.get().uri("/v1/models/{site}", site).retrieve().body(MlModelInfo::class.java)
    }

    override fun evaluation(site: String): MlEvaluationResponse? = try {
        http.get().uri("/v1/evaluation/{site}", site).retrieve().body(MlEvaluationResponse::class.java)
    } catch (e: RestClientException) {
        // 404 — эндпоинт ещё не сделан в ML; сетевые ошибки — ML не запущен. В обоих случаях читаем CSV теста
        log.info("ML evaluation for {} unavailable, falling back to CSV: {}", site, e.message)
        null
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
