package com.uniride.agent.dto;

/** Voice envelope: keeps the text chat contract untouched inside `respuesta`. */
public record VozChatResponse(
        String transcripcion,
        String textoHablado,
        ChatResponse respuesta) {
}
