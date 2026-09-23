package com.ybkuanysh.backend.ml

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Разбор настоящего ответа ML v2 — именно JSON, а не объектов: так ловятся ошибки имён полей. */
class MlModelsTests {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `parses all v2 fields of a real ML response`() {
        val raw = javaClass.getResource("/ml/forecast_v2_turbine_1_2026-02-10.json")!!.readText()
        val r = mapper.readValue(raw, MlForecastResponse::class.java)
        assertEquals("2026-02-10T07:00:00Z", r.issuedAtUtc)
        assertEquals("Asia/Almaty", r.localTz)
        assertEquals("2026-02-09T18:00:00Z", r.weatherIssuedBeforeUtc)
        assertEquals("strict", r.leakageMode)
        assertEquals(listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"), r.weatherModels)
        assertNotNull(r.modelVersion)
        val h = r.hourly.first()
        assertEquals("2026-02-10T19:00:00Z", h.timeUtc)
        assertEquals(14.19, h.windSpeed100m)
        assertNotNull(h.temperature2m)
        assertNotNull(h.windSpread)
        assertEquals(48, r.hourly.count { it.windSpeed100m != null })
        assertNotNull(r.analysis!!.issues.first().fromUtc)
    }
}
