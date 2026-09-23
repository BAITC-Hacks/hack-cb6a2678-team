package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentToolCall
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.dto.ForecastRevisionsResponse
import com.ybkuanysh.backend.dto.ForecastSummary
import com.ybkuanysh.backend.dto.MetricsResponse
import com.ybkuanysh.backend.dto.Turbine
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.metrics.MetricsService
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.weather.WeatherService
import com.ybkuanysh.backend.forecast.ForecastAnalytics
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import java.time.format.DateTimeParseException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Компактный вид прогноза для LLM: полный JSON на 48 точек съедает контекст локальной модели. */
data class AgentForecastView(
    val turbineId: String,
    val forecastIssuedAt: Instant,
    val weatherIssuedAt: Instant,
    val modelVersion: String,
    val conclusions: List<String>,
    val summary: ForecastSummary,
    val hourlyFormat: String?,
    val hourly: List<String>?,
)

/** Инструменты агента. Прогноз — ML-сервис, погода — Open-Meteo, метрики пока на моках. */
@Component
class AgentTools(
    private val turbines: TurbineRegistry,
    private val weather: WeatherService,
    private val forecasts: ForecastService,
    private val metrics: MetricsService,
    ml: MlProperties,
) {

    /** Дни и часы для агента — по местному времени станции: так спрашивает диспетчер и так ML задаёт сутки. */
    private val zone: ZoneId = ZoneId.of(ml.localTz)

    @Tool(description = "Список турбин ВЭС с идентификаторами и координатами")
    fun listTurbines(ctx: ToolContext): List<Turbine> =
        traced(ctx, "listTurbines", emptyMap()) { turbines.turbines }

    @Tool(
        description = "Прогноз выработки турбины (ML-модель) на календарные дни по местному времени. Прогноз выпускается накануне первого " +
            "дня в 12:00 местного времени (как прогноз на сутки вперёд), мощность нормализована 0..1. " +
            "conclusions — готовые выводы по каждому дню и предупреждения: отвечай по ним. " +
            "summary — итоги числами за весь запрошенный период. Почасовые строки hourly — только при detailed=true.",
    )
    fun getForecast(
        @ToolParam(description = "Идентификатор турбины, например t1") turbineId: String,
        @ToolParam(description = "Первый день, yyyy-MM-dd") date: String,
        @ToolParam(description = "Последний день включительно, yyyy-MM-dd; не больше 2 дней от первого. Пусто — один день", required = false)
        toDate: String?,
        @ToolParam(description = "true — добавить почасовые данные. Нужно, только если спрашивают про конкретные часы", required = false)
        detailed: Boolean?,
        ctx: ToolContext,
    ): AgentForecastView {
        val d = detailed ?: false
        return traced(ctx, "getForecast", mapOf("turbineId" to turbineId, "date" to date, "toDate" to toDate, "detailed" to d)) {
            val from = parseDate(date)
            val to = toDate?.takeIf { it.isNotBlank() }?.let { parseDate(it) } ?: from
            val days = ChronoUnit.DAYS.between(from, to) + 1
            require(days in 1..2) { "Период прогноза — 1 или 2 дня, получено: $date–$toDate" }
            // Выпуск накануне: его сутки D+1 и D+2 — это ровно запрошенные дни
            compact(forecasts.forecast(turbineId, from.minusDays(1), (days * 24).toInt()), d)
        }
    }

    @Tool(
        description = "Версии прогноза на один день: первая — из выпуска за 2 дня до него, вторая — из выпуска накануне " +
            "(по более свежему прогнозу погоды), и насколько изменилась средняя мощность",
    )
    fun getForecastRevisions(
        @ToolParam(description = "Идентификатор турбины, например t1") turbineId: String,
        @ToolParam(description = "День, yyyy-MM-dd") date: String,
        ctx: ToolContext,
    ): AgentRevisionsView = traced(ctx, "getForecastRevisions", mapOf("turbineId" to turbineId, "date" to date)) {
        revisionsView(forecasts.revisions(turbineId, parseDate(date)), zone)
    }

    @Tool(
        description = "Точность модели на отложенном тесте — январь 2026 (факта за февраль нет, поэтому точность за февраль " +
            "посчитать нельзя). Модель для теста обучена строго по 31.12.2025. Сравнение с бейзлайнами «мощность как в момент " +
            "прогноза» и «кривая мощности без ML». conclusions — готовые выводы: отвечай по ним. " +
            "Без дат — весь доступный период теста.",
    )
    fun getMetrics(
        @ToolParam(description = "Идентификатор турбины; пусто — по обеим турбинам", required = false) turbineId: String?,
        @ToolParam(description = "Начало периода, yyyy-MM-dd; пусто — начало теста", required = false) from: String?,
        @ToolParam(description = "Конец периода включительно, yyyy-MM-dd; пусто — конец теста", required = false) to: String?,
        ctx: ToolContext,
    ): AgentMetricsView {
        val id = turbineId?.takeIf { it.isNotBlank() }
        return traced(ctx, "getMetrics", mapOf("turbineId" to id, "from" to from, "to" to to)) {
            val period = metrics.period() ?: throw IllegalStateException("Нет данных отложенного теста")
            val f = from?.takeIf { it.isNotBlank() }?.let { parseDate(it) } ?: period.first
            val t = to?.takeIf { it.isNotBlank() }?.let { parseDate(it) } ?: period.second
            metricsView(metrics.metrics(id, f, t))
        }
    }

    @Tool(
        description = "Реальный прогноз погоды (ECMWF IFS, Open-Meteo) у турбины на календарные дни по местному времени — " +
            "в том виде, в каком он был известен в 00:00 местного времени первого дня (как прогноз на сутки вперёд). " +
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
            val turbine = turbines.requireTurbine(turbineId)
            val at = from.atStartOfDay(zone).toInstant()
            weather.forecastAt(turbine.lat, turbine.lon, at, (days * 24).toInt()).toAgentView(turbineId, d, zone)
        }
    }

    private fun parseDate(s: String): LocalDate = try {
        LocalDate.parse(s.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Неверный формат даты '$s': нужен yyyy-MM-dd")
    }

    private fun compact(fc: ForecastResponse, detailed: Boolean) = AgentForecastView(
        turbineId = fc.turbineId,
        forecastIssuedAt = fc.forecastIssuedAt,
        weatherIssuedAt = fc.weatherIssuedAt,
        modelVersion = fc.modelVersion,
        conclusions = forecastConclusions(fc, zone),
        summary = fc.summary,
        hourlyFormat = if (detailed) "время (${zoneLabel(zone)}) | прогноз | P10–P90 | ветер м/с | температура °C" else null,
        hourly = if (!detailed) null else fc.points.map {
            "${hourFormat(zone).format(it.timestamp)} | ${it.predictedPower} | ${it.p10}–${it.p90} | ${it.windSpeed ?: "-"} | ${it.temperature ?: "-"}"
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
    }
}

/** Метрики готовыми фразами: 31 день и 48 часов горизонта в JSON не нужны модели и съедают контекст. */
data class AgentMetricsView(val turbineId: String?, val period: String, val conclusions: List<String>)

internal fun metricsView(m: MetricsResponse): AgentMetricsView {
    val lines = buildList {
        add("Период теста ${m.periodFrom}…${m.periodTo} (${m.sampleHours} ч), прогноз на следующие сутки: средняя ошибка (MAE) " +
            "${pct(m.mae)} номинала, RMSE ${pct(m.rmse)}.")
        m.bias?.let {
            add(if (it > 0.02) "Модель в среднем ЗАВЫШАЕТ выработку на ${pct(it)} номинала."
                else if (it < -0.02) "Модель в среднем ЗАНИЖАЕТ выработку на ${pct(-it)} номинала."
                else "Систематического смещения почти нет (${pct(it)}).")
        }
        m.baselineMae?.let { add(compareLine("«мощность как в момент прогноза»", it, m.mae)) }
        m.powerCurveBaselineMae?.let { add(compareLine("«кривая мощности без ML»", it, m.mae)) }
        m.intervalCoverage?.let { add("Интервал P10–P90 накрыл факт в ${pct(it)} часов (ожидается около 80 %).") }
        m.byDay.maxByOrNull { it.mae }?.let { worst ->
            val best = m.byDay.minBy { it.mae }
            add("Лучший день ${best.date} (MAE ${pct(best.mae)}), худший ${worst.date} (MAE ${pct(worst.mae)}).")
        }
        if (m.byLeadTime.size >= 48) {
            val d1 = m.byLeadTime.filter { it.leadHour <= 24 }.map { it.mae }.average()
            val d2 = m.byLeadTime.filter { it.leadHour > 24 }.map { it.mae }.average()
            add("Ошибка на первых сутках горизонта ${pct(d1)}, на вторых ${pct(d2)}.")
        }
    }
    return AgentMetricsView(m.turbineId, "${m.periodFrom}…${m.periodTo}", lines)
}

private fun compareLine(name: String, baseline: Double, mae: Double): String {
    val diff = baseline - mae
    return if (diff > 0) "Бейзлайн $name: MAE ${pct(baseline)} — модель точнее на ${pct(diff)} номинала (в ${"%.1f".format(baseline / mae)} раза)."
    else "Бейзлайн $name: MAE ${pct(baseline)} — модель НЕ лучше этого бейзлайна."
}

/** Версии прогноза на день — готовыми фразами в местном времени (сырые UTC-метки модель подписывает наугад). */
data class AgentRevisionsView(val turbineId: String, val date: String, val conclusions: List<String>)

internal fun revisionsView(r: ForecastRevisionsResponse, zone: ZoneId): AgentRevisionsView {
    val hour = hourFormat(zone)
    val label = zoneLabel(zone)
    val lines = r.revisions.map {
        val change = it.changeVsPreviousPct?.let { c -> ", изменение к предыдущей версии ${if (c > 0) "+" else ""}$c %" } ?: ""
        "Версия ${it.revision}: выпущена ${hour.format(it.forecastIssuedAt)} $label по погоде, выпущенной не позже " +
            "${hour.format(it.weatherIssuedAt)} $label; средняя мощность за сутки ${it.meanPower} (${pct(it.meanPower)} номинала)$change."
    }
    return AgentRevisionsView(r.turbineId, r.date.toString(), lines.ifEmpty { listOf("Нет доступных версий прогноза на этот день.") })
}

/** Готовые выводы по прогнозу: по каждым местным суткам и предупреждения. Модель пересказывает их, а не считает. */
internal fun forecastConclusions(fc: ForecastResponse, zone: ZoneId): List<String> {
    val hour = hourFormat(zone)
    val label = zoneLabel(zone)
    val days = fc.points.groupBy { dayFormat(zone).format(it.timestamp) }.map { (day, points) ->
        val s = ForecastAnalytics.summary(points)
        "За $day: средняя мощность ${pct(s.meanPower)} номинала, пик ${pct(s.maxPower)} в ${hour.format(s.maxPowerAt)} $label, " +
            "часов почти без выработки (< ${pct(ForecastAnalytics.LOW_POWER)}): ${s.lowPowerHours} из ${points.size}."
    }
    // Одно предупреждение — одна строка со всеми его интервалами: так короче и модели проще пересказать
    val alerts = if (fc.alerts.isEmpty()) {
        listOf("Предупреждений нет.")
    } else {
        fc.alerts.groupBy { it.message }.map { (message, list) ->
            val intervals = list.joinToString(", ") {
                if (it.from == it.to) "в ${hour.format(it.from)}" else "${hour.format(it.from)}–${hour.format(it.to)}"
            }
            "Предупреждение (${list.first().severity}): $message — $intervals ($label)."
        }
    }
    val issued = "Прогноз выпущен ${hour.format(fc.forecastIssuedAt)} $label; использована погода, выпущенная не позже ${hour.format(fc.weatherIssuedAt)} $label."
    return days + alerts + issued
}

private fun pct(x: Double) = "${(x * 100).roundToInt()} %"
