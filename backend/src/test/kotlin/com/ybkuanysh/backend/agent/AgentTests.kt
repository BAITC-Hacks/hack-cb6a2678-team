package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.dto.AgentToolCall
import com.ybkuanysh.backend.mock.NotFoundException
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class AgentTests(@Autowired val mvc: MockMvc, @Autowired val tools: AgentTools) {

    private fun newContext() = mutableListOf<AgentToolCall>().let { it to ToolContext(mapOf(AgentTools.TRACE_KEY to it)) }

    @Test
    fun `tools record successful calls in trace`() {
        val (trace, ctx) = newContext()
        val fc = tools.getForecast("t1", "2026-02-05", 24, null, ctx)
        assertEquals(24, fc.hourly.size)
        assertEquals(listOf("getForecast"), trace.map { it.tool })
        assertTrue(trace.single().ok)
    }

    @Test
    fun `tools default horizon to 48 and treat blank turbine as all`() {
        val (trace, ctx) = newContext()
        assertEquals(48, tools.getForecast("t2", "2026-02-05", null, null, ctx).hourly.size)
        assertEquals(null, tools.getMetrics(" ", "2026-02-01", "2026-02-03", ctx).turbineId)
        assertEquals(2, trace.size)
    }

    @Test
    fun `tool failure is recorded and rethrown for the model`() {
        val (trace, ctx) = newContext()
        assertFailsWith<NotFoundException> { tools.getForecast("nope", "2026-02-05", 24, null, ctx) }
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
