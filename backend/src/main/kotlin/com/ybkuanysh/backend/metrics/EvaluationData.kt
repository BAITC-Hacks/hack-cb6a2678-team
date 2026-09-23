package com.ybkuanysh.backend.metrics

import com.ybkuanysh.backend.ml.MlForecastSource
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.ml.MlUnavailableException
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Час отложенного теста: прогноз тестовой модели (обучена до начала теста) и факт. */
data class EvalRow(
    val timeUtc: Instant,
    /** Сутки прогноза и час по местному времени станции. */
    val targetDate: LocalDate,
    val localHour: Int,
    val leadDay: Int,
    val forecast: Double,
    val p10: Double,
    val p90: Double,
    val actual: Double?,
    val pcBaseline: Double?,
    val anomaly: Boolean,
)

data class Evaluation(val site: String, val modelVersion: String?, val source: String, val rows: List<EvalRow>)

/** Отложенный тест ML: из `GET /v1/evaluation/{site}`, а пока его нет — из CSV теста в `ML/outputs/training`. */
@Component
class EvaluationRepository(private val ml: MlForecastSource, private val props: MlProperties) {

    private val cache = ConcurrentHashMap<String, Evaluation>()
    private val tz: ZoneId get() = ZoneId.of(props.localTz)

    fun evaluation(site: String): Evaluation = cache.computeIfAbsent(site) { load(it) }

    private fun load(site: String): Evaluation {
        ml.evaluation(site)?.let { r ->
            val rows = r.rows.map {
                val t = Instant.parse(it.timeUtc)
                val local = t.atZone(tz)
                EvalRow(t, local.toLocalDate(), local.hour, it.leadDay, it.forecast, it.p10, it.p90, it.actual, it.pcBaseline, it.anomaly)
            }
            return Evaluation(site, r.modelVersion, "ml:/v1/evaluation", rows)
        }
        val file = Path.of(props.evaluationDir, site, "test_predictions.csv")
        if (!Files.exists(file)) throw MlUnavailableException("Нет данных отложенного теста: ни /v1/evaluation, ни $file")
        val lines = Files.readAllLines(file)
        val col = lines.first().split(',').withIndex().associate { (i, name) -> name to i }
        val rows = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val v = line.split(',')
            fun num(name: String) = v[col.getValue(name)].takeIf { it.isNotEmpty() }?.toDouble()
            val local = LocalDateTime.parse(v[col.getValue("time_local")], CSV_TIME)
            EvalRow(
                timeUtc = local.atZone(tz).toInstant(),
                targetDate = local.toLocalDate(),
                localHour = local.hour,
                leadDay = v[col.getValue("lead_day")].toInt(),
                forecast = num("forecast")!!,
                p10 = num("p10")!!,
                p90 = num("p90")!!,
                actual = num("actual"),
                pcBaseline = num("pc_baseline"),
                anomaly = v[col.getValue("anomaly")].equals("True", ignoreCase = true),
            )
        }
        return Evaluation(site, null, "csv:$file", rows)
    }

    companion object {
        private val CSV_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** Почасовой факт SCADA (среднее за час, метка — начало часа, местное время) — для бейзлайна persistence. */
@Component
class ScadaRepository(private val props: MlProperties) {

    private val cache = ConcurrentHashMap<String, Map<LocalDateTime, Double>>()

    fun hourlyPower(site: String): Map<LocalDateTime, Double> = cache.computeIfAbsent(site) { load(it) }

    private fun load(site: String): Map<LocalDateTime, Double> {
        val file = Path.of(props.scadaDir, "$site.csv")
        if (!Files.exists(file)) return emptyMap()
        // ID,Статистическое время,Средняя скорость ветра,Нормализованная активная мощность,Температура
        return Files.readAllLines(file).drop(1).filter { it.isNotBlank() }
            .map { line -> line.split(',').let { LocalDateTime.parse(it[1], SCADA_TIME).withMinute(0) to it[3].toDouble() } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.average() }
    }

    companion object {
        /** Час без ведущего нуля: «2023-03-11 0:00:00». */
        private val SCADA_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss")
    }
}
