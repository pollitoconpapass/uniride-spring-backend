package com.uniride.agent;

import com.uniride.agent.dto.ChatRequest;
import com.uniride.agent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication auth) {
        return agentService.chat(auth.getName(), request.message());
    }
}
