package com.uniride.agent.controller;

import com.uniride.agent.AgentService;
import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.VozChatResponse;
import com.uniride.agent.voice.TextoHablado;
import com.uniride.agent.voice.TranscripcionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentVozController {

    private final TranscripcionService transcripcion;
    private final AgentService agentService;

    @PostMapping(value = "/voz", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VozChatResponse voz(@RequestPart("audio") MultipartFile audio,
                               @RequestParam(value = "conversationId", required = false) UUID conversationId) {
        String texto = transcripcion.transcribir(audio);
        ChatResponse respuesta = agentService.chatVoz(texto, conversationId);
        return new VozChatResponse(texto, TextoHablado.desde(respuesta), respuesta);
    }
}
