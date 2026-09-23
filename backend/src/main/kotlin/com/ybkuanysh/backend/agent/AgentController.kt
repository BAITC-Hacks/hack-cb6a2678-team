package com.ybkuanysh.backend.agent

import com.ybkuanysh.backend.api.BadRequestException
import com.ybkuanysh.backend.dto.AgentChatRequest
import com.ybkuanysh.backend.dto.AgentChatResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentController(private val agent: AgentService) {

    @PostMapping("/chat")
    fun chat(@RequestBody request: AgentChatRequest): AgentChatResponse {
        if (request.message.isBlank()) throw BadRequestException("message must not be blank")
        return agent.chat(request.message)
    }
}
