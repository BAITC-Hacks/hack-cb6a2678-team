package com.ybkuanysh.backend.cycle

import com.ybkuanysh.backend.agent.forecastConclusions
import com.ybkuanysh.backend.agent.hourFormat
import com.ybkuanysh.backend.dto.AgentStep
import com.ybkuanysh.backend.dto.AgentStepStatus
import com.ybkuanysh.backend.dto.CycleStatus
import com.ybkuanysh.backend.dto.ForecastResponse
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.forecast.IssuedForecast
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.ml.MlUnavailableException
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.weather.WeatherService
import com.ybkuanysh.backend.weather.WeatherUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Цикл агента для турбины и дня выпуска D:
 * погода у турбины → прогноз ML (с повторами) → проверка → сравнение с предыдущим выпуском → отчёт → сохранение.
 *
 * Порядок шагов фиксирован, чтобы прогноз получался всегда; решения внутри шагов — по данным:
 * какой прогон погоды взять, повторять ли ML, принять ли прогноз, существенно ли он изменился,
 * писать отчёт через LLM или шаблоном. Каждый шаг с его данными пишется в журнал (agent-log).
 */
@Service
class ForecastCycleService(
    private val forecasts: ForecastService,
    private val weather: WeatherService,
    private val turbines: TurbineRegistry,
    private val store: CycleStore,
    private val reportWriter: ReportWriter,
    private val props: CycleProperties,
    private val clock: Clock,
    ml: MlProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(ml.localTz)
    private val hour = hourFormat(zone)

    // Один поток: LLM всё равно обрабатывает запросы по одному
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "forecast-cycle").apply { isDaemon = true } }
    private val running = ConcurrentHashMap<String, String>()

    /** Запускает цикл (асинхронно, если cycle.async); если такой уже идёт — возвращает его id. */
    fun start(turbineId: String, issueDate: LocalDate, horizonHours: Int): String {
        turbines.requireTurbine(turbineId)
        val key = "$turbineId|$issueDate"
        running[key]?.let { return it }
        val cycleId = "cycle_${turbineId}_${issueDate}_${clock.instant().epochSecond}"
        running[key] = cycleId
        val task = Runnable {
            try {
                run(turbineId, issueDate, horizonHours, cycleId)
            } finally {
                running.remove(key)
            }
        }
        if (props.async) executor.execute(task) else task.run()
        return cycleId
    }

    fun run(turbineId: String, issueDate: LocalDate, horizonHours: Int, cycleId: String = "cycle_${turbineId}_$issueDate"): CycleRecord {
        val run = Run(cycleId, turbineId, issueDate, horizonHours)
        return try {
            execute(run)
        } catch (e: CycleFailed) {
            log.warn("Cycle {} failed: {}", cycleId, e.message)
            run.finish(CycleStatus.failed, forecast = null, reportSource = null)
        }
    }

    private fun execute(run: Run): CycleRecord {
        val turbine = turbines.requireTurbine(run.turbineId)
        val issuedAt = forecasts.issuedAt(run.issueDate)

        run.step("Погода у турбины", "fetchWeather") {
            try {
                val w = weather.forecastAt(turbine.lat, turbine.lon, issuedAt, run.horizonHours + 12)
                val skipped = if (w.skippedRuns.isEmpty()) "" else "; пропущено прогонов: ${w.skippedRuns.size} (${w.skippedRuns.first().reason})"
                StepResult<Unit>(
                    AgentStepStatus.success,
                    "Open-Meteo ${w.model}, прогон от ${hour.format(w.runInitAt)}, опубликован к ${hour.format(w.runAvailableAt)} " +
                        "— не позже момента прогноза ${hour.format(issuedAt)}$skipped",
                    dataIssuedAt = w.runInitAt,
                )
            } catch (e: WeatherUnavailableException) {
                // Погода бэкенда нужна для алертов и отображения; прогноз ML от неё не зависит — продолжаем
                StepResult<Unit>(AgentStepStatus.failed, "Погода недоступна (${e.message}); алерты по погоде будут неполными")
            }
        }

        val issued = run.step("Прогноз ML", "predict") { mlWithRetries(run) }
        val fc = issued.response

        run.step("Проверка прогноза", "validate") {
            val problems = validate(fc, issuedAt, run.horizonHours)
            if (problems.isNotEmpty()) {
                throw CycleFailed(StepResult<Unit>(AgentStepStatus.failed, "Прогноз отклонён: ${problems.joinToString("; ")}"))
            }
            StepResult<Unit>(AgentStepStatus.success, "Все проверки пройдены: ${run.horizonHours} ч подряд, мощность в 0..1, p10 ≤ прогноз ≤ p90, погода не из будущего")
        }

        val comparison = run.step("Сравнение с предыдущим выпуском", "compareWithPrevious") { compare(run, issued) }

        val facts = forecastConclusions(fc, zone) + comparison
        val (report, source) = run.step("Отчёт для диспетчера", "llm") {
            val text = reportWriter.write(facts)
            val invented = text?.let { unsupportedValues(it, facts) }.orEmpty()
            if (text != null && invented.isEmpty()) {
                StepResult(AgentStepStatus.success, "Отчёт написан LLM и прошёл самопроверку: все времена и проценты есть в данных", value = text to "llm")
            } else if (text != null) {
                // LLM выдумала значения — такой отчёт диспетчеру не показываем
                StepResult(
                    AgentStepStatus.success,
                    "Отчёт LLM отклонён самопроверкой (нет в данных: ${invented.joinToString(", ")}) — отчёт по шаблону",
                    value = templateReport(facts) to "template",
                )
            } else {
                StepResult(AgentStepStatus.success, "LLM недоступна или выключена — отчёт по шаблону", value = templateReport(facts) to "template")
            }
        }

        val final = fc.copy(agentReport = report)
        run.step("Сохранение", "saveForecast") {
            StepResult<Unit>(AgentStepStatus.success, "Прогноз сохранён: ${final.points.size} ч, предупреждений ${final.alerts.size}")
        }
        return run.finish(CycleStatus.success, final, source)
    }

    private fun mlWithRetries(run: Run): StepResult<IssuedForecast> {
        var attempt = 1
        while (true) {
            try {
                val issued = forecasts.issued(run.turbineId, run.issueDate, run.horizonHours)
                val fc = issued.response
                return StepResult(
                    AgentStepStatus.success,
                    "Модель ${fc.modelVersion}: ${fc.points.size} ч, погода ${fc.weatherSource} выпущена не позже " +
                        "${hour.format(fc.weatherIssuedAt)}" + if (attempt > 1) " (с попытки $attempt)" else "",
                    dataIssuedAt = fc.weatherIssuedAt,
                    value = issued,
                )
            } catch (e: MlUnavailableException) {
                if (attempt > props.mlRetries) {
                    throw CycleFailed(StepResult<Unit>(AgentStepStatus.failed, "ML недоступен после $attempt попыток: ${e.message}"))
                }
                run.note(AgentStepStatus.retrying, "Прогноз ML", "predict", "Попытка $attempt не удалась (${e.message}), повтор")
                attempt++
                Thread.sleep(props.retryDelay.toMillis())
            }
        }
    }

    private fun validate(fc: ForecastResponse, issuedAt: Instant, horizonHours: Int): List<String> = buildList {
        if (fc.points.size != horizonHours) add("${fc.points.size} ч вместо $horizonHours")
        if (fc.points.zipWithNext().any { (a, b) -> Duration.between(a.timestamp, b.timestamp) != Duration.ofHours(1) }) add("часы идут не подряд")
        if (fc.points.any { it.predictedPower !in 0.0..1.0 }) add("мощность вне 0..1")
        if (fc.points.any { it.p10 == null || it.p90 == null || it.predictedPower !in it.p10..it.p90 }) add("прогноз вне интервала p10–p90")
        if (fc.weatherIssuedAt.isAfter(issuedAt)) add("погода выпущена позже момента прогноза")
        if (fc.points.first().timestamp.isBefore(issuedAt)) add("прогноз начинается раньше момента выпуска")
    }

    /** Сутки D+1 текущего выпуска — это сутки D+2 предыдущего: сравниваем среднюю мощность. Значение — вывод для отчёта. */
    private fun compare(run: Run, current: IssuedForecast): StepResult<String> {
        val previous = try {
            forecasts.issued(run.turbineId, run.issueDate.minusDays(1), 48)
        } catch (e: MlUnavailableException) {
            val text = "Предыдущего выпуска нет — сравнивать не с чем."
            return StepResult(AgentStepStatus.success, text, value = text)
        }
        val now = current.response.points.filterIndexed { i, _ -> current.leadDays[i] == 1 }.map { it.predictedPower }.average()
        val before = previous.response.points.filterIndexed { i, _ -> previous.leadDays[i] == 2 }.map { it.predictedPower }.average()
        val day = run.issueDate.plusDays(1)
        val diff = now - before
        val verdict = if (abs(diff) >= props.significantChange) {
            "существенное изменение — прогноз погоды обновился, версия заменяет предыдущую"
        } else {
            "изменение небольшое, прогноз подтверждён"
        }
        val text = "Сутки ${day.dayOfMonth.toString().padStart(2, '0')}.${day.monthValue.toString().padStart(2, '0')}: было ${pct(before)} номинала " +
            "(выпуск ${run.issueDate.minusDays(1)}), стало ${pct(now)} (${if (diff >= 0) "+" else ""}${pct(diff)}) — $verdict."
        return StepResult(AgentStepStatus.success, text, value = text)
    }

    private fun templateReport(facts: List<String>): String = facts.joinToString(" ")

    private fun pct(x: Double) = "${Math.round(x * 100)} %"

    /** Результат шага: статус и текст для журнала, данные для следующих шагов. */
    private data class StepResult<T>(
        val status: AgentStepStatus,
        val details: String?,
        val dataIssuedAt: Instant? = null,
        val value: T? = null,
    )

    private class CycleFailed(val result: StepResult<*>) : RuntimeException(result.details)

    /** Идущий цикл: шаги пишутся в хранилище сразу, чтобы фронт видел прогресс при поллинге. */
    private inner class Run(val cycleId: String, val turbineId: String, val issueDate: LocalDate, val horizonHours: Int) {
        private val startedAt = clock.instant()
        private val steps = mutableListOf<AgentStep>()

        init {
            publish(CycleStatus.running, null, null, persist = false)
        }

        fun <T> step(name: String, tool: String, block: () -> StepResult<T>): T {
            steps += AgentStep(name, AgentStepStatus.running, clock.instant(), null, tool, null)
            publish(CycleStatus.running, null, null, persist = false)
            val result = try {
                block()
            } catch (e: CycleFailed) {
                complete(name, e.result)
                throw e
            }
            complete(name, result)
            @Suppress("UNCHECKED_CAST")
            return result.value as T
        }

        private fun complete(name: String, r: StepResult<*>) {
            val i = steps.indexOfLast { it.stepName == name && it.status == AgentStepStatus.running }
            steps[i] = steps[i].copy(status = r.status, details = r.details, dataIssuedAt = r.dataIssuedAt)
            publish(CycleStatus.running, null, null, persist = false)
        }

        /** Промежуточная запись (например, неудачная попытка) перед текущим шагом. */
        fun note(status: AgentStepStatus, name: String, tool: String, details: String) {
            steps.add(steps.size - 1, AgentStep(name, status, clock.instant(), details, tool, null))
            publish(CycleStatus.running, null, null, persist = false)
        }

        fun finish(status: CycleStatus, forecast: ForecastResponse?, reportSource: String?): CycleRecord =
            publish(status, forecast, reportSource, persist = status == CycleStatus.success)

        private fun publish(status: CycleStatus, forecast: ForecastResponse?, reportSource: String?, persist: Boolean): CycleRecord {
            val record = CycleRecord(
                cycleId, turbineId, issueDate, horizonHours, status, startedAt,
                finishedAt = if (status == CycleStatus.running) null else clock.instant(),
                steps = steps.toList(), forecast = forecast, reportSource = reportSource,
            )
            store.save(record, persist)
            return record
        }
    }
}
