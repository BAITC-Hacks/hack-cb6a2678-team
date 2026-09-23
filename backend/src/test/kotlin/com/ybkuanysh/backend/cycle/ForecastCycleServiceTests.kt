package com.ybkuanysh.backend.cycle

import com.ybkuanysh.backend.dto.AgentStepStatus
import com.ybkuanysh.backend.dto.CycleStatus
import com.ybkuanysh.backend.forecast.ForecastService
import com.ybkuanysh.backend.ml.MlForecastSource
import com.ybkuanysh.backend.ml.MlProperties
import com.ybkuanysh.backend.ml.MlUnavailableException
import com.ybkuanysh.backend.support.FixtureMl
import com.ybkuanysh.backend.turbine.TurbineRegistry
import com.ybkuanysh.backend.weather.WeatherProperties
import com.ybkuanysh.backend.weather.WeatherRunSource
import com.ybkuanysh.backend.weather.WeatherService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastCycleServiceTests {

    @TempDir
    lateinit var dir: Path

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val feb4: LocalDate = LocalDate.parse("2026-02-04")

    private fun props(persist: Boolean = false) =
        CycleProperties(storeDir = if (persist) dir.resolve("cycles").toString() else "", async = false, retryDelay = Duration.ZERO)

    private fun service(
        ml: MlForecastSource = FixtureMl(),
        writer: ReportWriter = ReportWriter { null },
        props: CycleProperties = props(),
    ): Pair<ForecastCycleService, CycleStore> {
        val weather = WeatherService(
            WeatherRunSource { _, _, _, _ -> throw com.ybkuanysh.backend.weather.WeatherUnavailableException("offline") },
            WeatherProperties(cacheDir = dir.resolve("weather").toString(), offline = true),
            mapper,
        )
        val mlProps = MlProperties()
        val turbines = TurbineRegistry()
        val store = CycleStore(props, mapper)
        val forecasts = ForecastService(ml, mlProps, weather, turbines)
        return ForecastCycleService(forecasts, weather, turbines, store, writer, props, Clock.systemUTC(), mlProps) to store
    }

    @Test
    fun `successful cycle with LLM report`() {
        var facts: List<String> = emptyList()
        val (svc, _) = service(writer = ReportWriter { facts = it; "Отчёт LLM" })
        val r = svc.run("t1", feb4, 48)
        assertEquals(CycleStatus.success, r.status)
        assertEquals("llm", r.reportSource)
        assertEquals("Отчёт LLM", r.forecast!!.agentReport)
        assertEquals(
            listOf("fetchWeather", "predict", "validate", "compareWithPrevious", "llm", "saveForecast"),
            r.steps.map { it.tool },
        )
        // Отчёту передаются готовые выводы: дни, предупреждения и сравнение с предыдущим выпуском
        assertTrue(facts.first().startsWith("За 05.02.2026"))
        assertTrue(facts.last().startsWith("Сутки 05.02: было"))
        assertNotNull(r.finishedAt)
    }

    @Test
    fun `template report when LLM is unavailable`() {
        val r = service().first.run("t2", feb4, 24)
        assertEquals("template", r.reportSource)
        assertTrue(r.forecast!!.agentReport!!.startsWith("За 05.02.2026"))
        assertEquals(24, r.forecast!!.points.size)
    }

    @Test
    fun `ML is retried and the retry is logged`() {
        var calls = 0
        val flaky = object : MlForecastSource by FixtureMl() {
            override fun forecast(site: String, issueDate: LocalDate) =
                if (issueDate == feb4 && calls++ == 0) throw MlUnavailableException("timeout") else FixtureMl().forecast(site, issueDate)
        }
        val r = service(ml = flaky).first.run("t1", feb4, 48)
        assertEquals(CycleStatus.success, r.status)
        assertEquals(listOf(AgentStepStatus.retrying, AgentStepStatus.success), r.steps.filter { it.tool == "predict" }.map { it.status })
        assertTrue(r.steps.last { it.tool == "predict" }.details!!.contains("с попытки 2"))
    }

    @Test
    fun `invalid forecast is rejected and not saved`() {
        val broken = FixtureMl { resp -> resp.copy(hourly = resp.hourly.map { it.copy(p10 = 0.99, p90 = 1.0, forecast = 0.1) }) }
        val (svc, store) = service(ml = broken, props = props(persist = true))
        val r = svc.run("t1", feb4, 48)
        assertEquals(CycleStatus.failed, r.status)
        assertNull(r.forecast)
        val validate = r.steps.single { it.tool == "validate" }
        assertEquals(AgentStepStatus.failed, validate.status)
        assertTrue(validate.details!!.contains("прогноз вне интервала"))
        assertTrue(Files.notExists(dir.resolve("cycles/t1/2026-02-04.json")))
        assertEquals(CycleStatus.failed, store.find("t1", feb4)!!.status)
    }

    @Test
    fun `successful cycle is persisted and read back by a fresh store`() {
        val p = props(persist = true)
        service(props = p).first.run("t2", feb4, 48)
        assertTrue(Files.exists(dir.resolve("cycles/t2/2026-02-04.json")))
        val reloaded = CycleStore(p, mapper).find("t2", feb4)!!
        assertEquals(CycleStatus.success, reloaded.status)
        assertEquals(48, reloaded.forecast!!.points.size)
        assertEquals(6, reloaded.steps.size)
    }

    @Test
    fun `comparison notes when there is no previous issue`() {
        // Выпуска 30.01 в фикстурах нет
        val r = service().first.run("t1", LocalDate.parse("2026-01-31"), 48)
        assertEquals("Предыдущего выпуска нет — сравнивать не с чем.", r.steps.single { it.tool == "compareWithPrevious" }.details)
    }
}

class ReportSelfCheckTests {

    private val facts = listOf(
        "За 21.02.2026: средняя мощность 52 % номинала, пик 69 % в 21.02 23:00 местного времени.",
        "Предупреждение (info): Резкий рост выработки (≥ 0.3 за 3 ч) — в 21.02 12:00, 22.02 13:00–22.02 16:00 (местного времени).",
        "Сутки 21.02: было 59 % номинала, стало 52 % (-7 %).",
    )

    @Test
    fun `report with values from facts passes`() {
        val ok = "21 февраля в среднем 52 % с пиком 69 % в 23:00; резкий рост в 12:00 и с 13:00 до 16:00; прогноз снизился на 7 %."
        assertEquals(emptyList(), unsupportedValues(ok, facts))
    }

    @Test
    fun `invented times and percents are caught`() {
        val bad = "Рост ожидается с 21:00 до 23:00 и утром с 05:00 до 08:00, средняя мощность 55 %."
        assertEquals(listOf("21:00", "05:00", "08:00", "55 %"), unsupportedValues(bad, facts))
        // «2 %» не должно совпасть с «52 %»
        assertEquals(listOf("2 %"), unsupportedValues("изменение 2 %", facts))
    }
}
