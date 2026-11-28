package com.uniride.agent.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "El mensaje es obligatorio")
        @Size(max = 1000, message = "El mensaje no puede superar los 1000 caracteres")
        String message,
        java.util.UUID conversationId) {
    public ChatRequest(String message) { this(message, null); }
}
