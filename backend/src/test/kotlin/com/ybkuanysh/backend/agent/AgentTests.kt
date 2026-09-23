package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentToolCall
import com.ybkuanysh.backend.mock.NotFoundException
import org.junit.jupiter.api.Test
import java.time.Instant
import org.springframework.ai.chat.model.ToolContext
import org.springframework.beans.factory.annotation.Autowired
import com.ybkuanysh.backend.support.FixtureMlConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(FixtureMlConfig::class)
@AutoConfigureMockMvc
class AgentTests(@Autowired val mvc: MockMvc, @Autowired val tools: AgentTools) {

    private fun newContext() = mutableListOf<AgentToolCall>().let { it to ToolContext(mapOf(AgentTools.TRACE_KEY to it)) }

    @Test
    fun `forecast tool returns the requested local day from the previous day issue`() {
        val (trace, ctx) = newContext()
        val fc = tools.getForecast("t1", "2026-02-05", null, null, ctx)
        // Сутки 05.02 местного времени — из выпуска 04.02 12:00 местного
        assertEquals(Instant.parse("2026-02-04T07:00:00Z"), fc.forecastIssuedAt)
        assertEquals(null, fc.hourly)
        assertTrue(fc.conclusions.first().startsWith("За 05.02.2026: средняя мощность"))
        assertEquals(1, fc.conclusions.count { it.startsWith("За ") })
        assertEquals(listOf("getForecast"), trace.map { it.tool })
        assertTrue(trace.single().ok)
    }

    @Test
    fun `forecast tool covers two days and metrics treat blank turbine as all`() {
        val (trace, ctx) = newContext()
        val fc = tools.getForecast("t2", "2026-02-04", "2026-02-05", true, ctx)
        assertEquals(48, fc.hourly!!.size)
        assertEquals(listOf("За 04.02.2026", "За 05.02.2026"), fc.conclusions.filter { it.startsWith("За ") }.map { it.substringBefore(":") })
        val metrics = tools.getMetrics(" ", null, null, ctx)
        assertEquals(null, metrics.turbineId)
        assertEquals("2026-01-01…2026-01-03", metrics.period)
        assertTrue(metrics.conclusions.any { it.contains("ЗАВЫШАЕТ") || it.contains("смещения") })
        assertEquals(2, trace.size)
    }

    @Test
    fun `tool failure is recorded and rethrown for the model`() {
        val (trace, ctx) = newContext()
        assertFailsWith<NotFoundException> { tools.getForecast("nope", "2026-02-05", null, null, ctx) }
        assertFalse(trace.single().ok)
    }

    @Test
    fun `blank message is 400`() {
        mvc.post("/api/agent/chat") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":"  "}"""
        }.andExpect { status { isBadRequest() } }
    }
}
