package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentToolCall
import com.ybkuanysh.backend.dto.ForecastAlert
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.ForecastRevisionsResponse
import com.ybkuanysh.backend.dto.ForecastSummary
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.Turbine
import com.ybkuanysh.backend.mock.MockDataService
import com.ybkuanysh.backend.weather.WeatherService
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeParseException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Компактный вид прогноза для LLM: полный JSON на 48 точек съедает контекст локальной модели. */
data class AgentForecastView(
    val turbineId: String,
    val forecastIssuedAt: Instant,
    val revision: Int,
    val weatherIssuedAt: Instant,
    val summary: ForecastSummary,
    val alerts: List<ForecastAlert>,
    val hourlyFormat: String,
    val hourly: List<String>,
)

/**
 * Инструменты агента. Погода — реальная (Open-Meteo), прогноз мощности и метрики пока на моках;
 * когда появится ML-сервис, меняется только реализация — сигнатуры и описания для LLM остаются.
 */
@Component
class AgentTools(private val mock: MockDataService, private val weather: WeatherService) {

    @Tool(description = "Список турбин ВЭС с идентификаторами и координатами")
    fun listTurbines(ctx: ToolContext): List<Turbine> =
        traced(ctx, "listTurbines", emptyMap()) { mock.turbines }

    @Tool(
        description = "Почасовой прогноз нормализованной мощности (0..1) турбины, сформированный на указанную дату. " +
            "summary — готовые итоги (средняя, минимум, пик, часы полной загрузки, часы простоя), alerts — предупреждения. " +
            "hourly — по строке на час (время UTC), формат описан в hourlyFormat.",
    )
    fun getForecast(
        @ToolParam(description = "Идентификатор турбины, например t1") turbineId: String,
        @ToolParam(description = "Дата прогноза в формате yyyy-MM-dd") date: String,
        @ToolParam(description = "Горизонт прогноза: 24 или 48 часов", required = false) horizonHours: Int?,
        @ToolParam(description = "Ревизия 1..4 (1 — плановая в 00:00 UTC, далее пересчёты каждые 6 ч)", required = false) revision: Int?,
        ctx: ToolContext,
    ): AgentForecastView {
        val h = if (horizonHours == 24) 24 else 48
        val rev = revision?.coerceIn(1, MockDataService.REVISIONS_PER_DAY) ?: 1
        return traced(ctx, "getForecast", mapOf("turbineId" to turbineId, "date" to date, "horizonHours" to h, "revision" to rev)) {
            compact(mock.forecast(turbineId, LocalDate.parse(date), h, rev))
        }
    }

    @Tool(description = "Ревизии прогноза за дату: когда пересчитывался прогноз, по какому прогону погоды и насколько менялась средняя мощность")
    fun getForecastRevisions(
        @ToolParam(description = "Идентификатор турбины, например t1") turbineId: String,
        @ToolParam(description = "Дата в формате yyyy-MM-dd") date: String,
        ctx: ToolContext,
    ): ForecastRevisionsResponse = traced(ctx, "getForecastRevisions", mapOf("turbineId" to turbineId, "date" to date)) {
        mock.revisions(turbineId, LocalDate.parse(date))
    }

    @Tool(description = "Метрики качества прогноза за период: MAE, RMSE, MAPE, MAE наивного бейзлайна и разбивка по дням")
    fun getMetrics(
        @ToolParam(description = "Идентификатор турбины; пусто — по всем турбинам", required = false) turbineId: String?,
        @ToolParam(description = "Начало периода, yyyy-MM-dd") from: String,
        @ToolParam(description = "Конец периода включительно, yyyy-MM-dd") to: String,
        ctx: ToolContext,
    ): MetricsResponse {
        val id = turbineId?.takeIf { it.isNotBlank() }
        return traced(ctx, "getMetrics", mapOf("turbineId" to id, "from" to from, "to" to to)) {
            mock.metrics(id, LocalDate.parse(from), LocalDate.parse(to))
        }
    }

    @Tool(
        description = "Реальный прогноз погоды (ECMWF IFS, Open-Meteo) у турбины на календарные дни UTC — " +
            "в том виде, в каком он был известен в 00:00 UTC первого дня (как прогноз на сутки вперёд). " +
            "conclusions — готовые выводы по каждому дню («За dd.MM.yyyy: …») и, если дней несколько, за весь период: " +
            "отвечай по ним и не делай своих выводов о рисках. Почасовые строки hourly — только при detailed=true.",
    )
    fun getWeather(
        @ToolParam(description = "Идентификатор турбины, например t1") turbineId: String,
        @ToolParam(description = "Первый день, yyyy-MM-dd") date: String,
        @ToolParam(description = "Последний день включительно, yyyy-MM-dd; не больше 3 дней от первого. Пусто — один день", required = false)
        toDate: String?,
        @ToolParam(description = "true — добавить почасовые данные. Нужно, только если спрашивают про конкретные часы", required = false)
        detailed: Boolean?,
        ctx: ToolContext,
    ): AgentWeatherView {
        val d = detailed ?: false
        return traced(ctx, "getWeather", mapOf("turbineId" to turbineId, "date" to date, "toDate" to toDate, "detailed" to d)) {
            val from = parseDate(date)
            val to = toDate?.takeIf { it.isNotBlank() }?.let { parseDate(it) } ?: from
            val days = ChronoUnit.DAYS.between(from, to) + 1
            require(days in 1..MAX_WEATHER_DAYS) { "Период должен быть от 1 до $MAX_WEATHER_DAYS дней, получено: $date–$toDate" }
            val turbine = mock.requireTurbine(turbineId)
            val at = from.atStartOfDay().toInstant(ZoneOffset.UTC)
            weather.forecastAt(turbine.lat, turbine.lon, at, (days * 24).toInt()).toAgentView(turbineId, d)
        }
    }

    private fun parseDate(s: String): LocalDate = try {
        LocalDate.parse(s.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Неверный формат даты '$s': нужен yyyy-MM-dd")
    }

    private fun compact(fc: ForecastResponse) = AgentForecastView(
        turbineId = fc.turbineId,
        forecastIssuedAt = fc.forecastIssuedAt,
        revision = fc.revision,
        weatherIssuedAt = fc.weatherIssuedAt,
        summary = fc.summary,
        alerts = fc.alerts,
        hourlyFormat = "время | P50 | P10–P90 | факт (- если неизвестен) | ветер м/с | температура °C",
        hourly = fc.points.map {
            "${HOUR.format(it.timestamp)} | ${it.predictedPower} | ${it.p10}–${it.p90} | ${it.actualPower ?: "-"} | ${it.windSpeed} | ${it.temperature}"
        },
    )

    /** Пишет вызов в журнал запроса, чтобы API мог показать, какие инструменты использовал агент. */
    private fun <T> traced(ctx: ToolContext, name: String, args: Map<String, Any?>, block: () -> T): T {
        val trace = ctx.context[TRACE_KEY] as MutableList<AgentToolCall>
        return try {
            block().also { trace += AgentToolCall(name, args, ok = true, error = null) }
        } catch (e: RuntimeException) {
            trace += AgentToolCall(name, args, ok = false, error = e.message)
            throw e
        }
    }

    companion object {
        const val TRACE_KEY = "trace"
        private const val MAX_WEATHER_DAYS = 3
        private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneOffset.UTC)
    }
}
