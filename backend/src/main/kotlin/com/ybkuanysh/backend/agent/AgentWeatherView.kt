package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.weather.WeatherForecast
import com.ybkuanysh.backend.weather.WeatherHour
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.round

/**
 * Компактный вид прогноза погоды для LLM: готовые выводы + по строке на час, всё время — местное.
 * Сырых меток UTC и JSON-сводки нет: модель брала время оттуда и подписывала его как местное.
 */
data class AgentWeatherView(
    val turbineId: String,
    val model: String,
    val skippedRuns: List<String>,
    /** Готовые выводы на русском — маленькая LLM пересказывает их, а не делает выводы сама. */
    val conclusions: List<String>,
    val hourlyFormat: String?,
    val hourly: List<String>?,
)

data class WeatherSummary(
    val hours: Int,
    val meanWind100m: Double?,
    val maxWind100m: Double?,
    val maxWind100mAt: Instant?,
    val maxGust10m: Double?,
    val minTemperature: Double?,
    val maxTemperature: Double?,
    /** Часы с ветром на 100 м ниже порога включения типичной турбины (3 м/с). */
    val calmHours: Int,
    /** Часы с ветром на 100 м от 23 м/с — близко к штормовому отключению (~25 м/с). */
    val stormHours: Int,
    /** Грубая эвристика обледенения: −5…+1 °C и влажность ≥ 90 %. */
    val icingRiskHours: Int,
)

internal fun hourFormat(zone: ZoneId): DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone)
internal fun dayFormat(zone: ZoneId): DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)

/** Подпись к времени: агент отвечает диспетчеру по местному времени станции. */
internal fun zoneLabel(zone: ZoneId): String = if (zone == ZoneOffset.UTC) "UTC" else "местного времени"

/**
 * [detailed] — добавить почасовые строки; без них ответ короче и модель реже путает цифры.
 * Часы горизонта должны покрывать целые сутки: значение с меткой T относится к часу (T − 1 ч, T].
 */
fun WeatherForecast.toAgentView(turbineId: String, detailed: Boolean, zone: ZoneId = ZoneOffset.UTC): AgentWeatherView {
    val summary = summarize(hours)
    val hour = hourFormat(zone)
    val days = hours.groupBy { dayOf(it, zone) }
    return AgentWeatherView(
        turbineId = turbineId,
        model = model,
        skippedRuns = skippedRuns.map { "${hour.format(it.runInitAt)}: ${it.reason}" },
        // Итог за период — только если дней несколько: иначе модель путает его со строкой дня
        conclusions = dailyConclusions(hours, zone) +
            (if (days.size > 1) periodConclusions(summary, days.keys, zone) else emptyList()) +
            (
                "Прогноз погоды $model: прогон от ${hour.format(runInitAt)}, опубликован к ${hour.format(runAvailableAt)} " +
                    "${zoneLabel(zone)} — известен на момент ${hour.format(forecastAt)}."
            ),
        hourlyFormat = if (detailed) "время (${zoneLabel(zone)}) | ветер 100 м, м/с | порывы 10 м, м/с | направление ° | температура °C | влажность %" else null,
        hourly = if (!detailed) null else hours.map {
            "${hour.format(it.timestamp)} | ${it.windSpeed100m ?: "-"} | ${it.windGusts10m ?: "-"} | " +
                "${it.windDirection100m?.toInt() ?: "-"} | ${it.temperature2m ?: "-"} | ${it.relativeHumidity2m?.toInt() ?: "-"}"
        },
    )
}

private fun periodConclusions(s: WeatherSummary, days: Collection<String>, zone: ZoneId): List<String> =
    conclusions(s, zone).map { "За весь период ${days.first()}–${days.last()}: $it" }

internal fun conclusions(s: WeatherSummary, zone: ZoneId = ZoneOffset.UTC): List<String> = buildList {
    if (s.meanWind100m != null && s.maxWind100m != null) {
        add("Ветер на 100 м: в среднем ${s.meanWind100m} м/с, максимум ${s.maxWind100m} м/с в ${hourFormat(zone).format(s.maxWind100mAt)} ${zoneLabel(zone)}.")
    }
    s.maxGust10m?.let { add("Максимальные порывы у земли (10 м): $it м/с.") }
    if (s.minTemperature != null && s.maxTemperature != null) {
        add("Температура от ${s.minTemperature} до ${s.maxTemperature} °C.")
    }
    add(
        if (s.stormHours > 0) "РИСК ШТОРМОВОГО ОТКЛЮЧЕНИЯ: ${s.stormHours} ч с ветром ≥ 23 м/с."
        else "Штормового отключения не ожидается: ветер на 100 м не достигает 23 м/с.",
    )
    add(
        if (s.icingRiskHours > 0) "РИСК ОБЛЕДЕНЕНИЯ: ${s.icingRiskHours} ч (−5…+1 °C и влажность ≥ 90 %)."
        else "Риска обледенения нет.",
    )
    add(
        if (s.calmHours > 0) "Штиль (ветер < 3 м/с, турбина стоит): ${s.calmHours} ч из ${s.hours}."
        else "Штилевых часов нет.",
    )
}

internal fun summarize(hours: List<WeatherHour>): WeatherSummary {
    val wind = hours.mapNotNull { it.windSpeed100m }
    val peak = hours.filter { it.windSpeed100m != null }.maxByOrNull { it.windSpeed100m!! }
    val temps = hours.mapNotNull { it.temperature2m }
    return WeatherSummary(
        hours = hours.size,
        meanWind100m = wind.takeIf { it.isNotEmpty() }?.let { r1(it.average()) },
        maxWind100m = peak?.windSpeed100m,
        maxWind100mAt = peak?.timestamp,
        maxGust10m = hours.mapNotNull { it.windGusts10m }.maxOrNull(),
        minTemperature = temps.minOrNull(),
        maxTemperature = temps.maxOrNull(),
        calmHours = wind.count { it < 3.0 },
        stormHours = wind.count { it >= 23.0 },
        icingRiskHours = hours.count {
            val t = it.temperature2m
            val rh = it.relativeHumidity2m
            t != null && rh != null && t in -5.0..1.0 && rh >= 90.0
        },
    )
}

/** Сутки, к которым относится час: значение в 00:00 закрывает последний час предыдущих суток. */
private fun dayOf(h: WeatherHour, zone: ZoneId): String = dayFormat(zone).format(h.timestamp.minusSeconds(3600))

/** Итоги по каждым суткам: модель плохо сама определяет, к какому дню относятся часы горизонта. */
internal fun dailyConclusions(hours: List<WeatherHour>, zone: ZoneId = ZoneOffset.UTC): List<String> =
    hours.groupBy { dayOf(it, zone) }.map { (day, dayHours) ->
        val s = summarize(dayHours)
        val wind = if (s.maxWind100m != null) {
            "ветер на 100 м в среднем ${s.meanWind100m} м/с, максимум ${s.maxWind100m} м/с в ${hourFormat(zone).format(s.maxWind100mAt)} ${zoneLabel(zone)}"
        } else {
            "нет данных о ветре"
        }
        val gust = s.maxGust10m?.let { ", порывы у земли до $it м/с" } ?: ""
        val temp = if (s.minTemperature != null) ", температура ${s.minTemperature}…${s.maxTemperature} °C" else ""
        val risks = listOf(
            if (s.stormHours > 0) "РИСК ШТОРМОВОГО ОТКЛЮЧЕНИЯ ${s.stormHours} ч" else "штормового отключения нет",
            if (s.icingRiskHours > 0) "РИСК ОБЛЕДЕНЕНИЯ ${s.icingRiskHours} ч" else "обледенения нет",
            if (s.calmHours > 0) "штиль ${s.calmHours} ч" else "штиля нет",
        ).joinToString(", ")
        "За $day: $wind$gust$temp; $risks."
    }

private fun r1(x: Double) = round(x * 10) / 10
