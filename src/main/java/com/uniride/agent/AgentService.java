package com.uniride.agent;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.entities.Usuario;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentService {

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatClient chatClient;
    private final UsuarioService usuarioService;
    private final Clock clock;

    public ChatResponse chat(String username, String message) {
        Usuario usuario = usuarioService.usuarioActual();
        String contexto = "Contexto: hoy es " + LocalDate.now(clock) + " y son las "
                + LocalTime.now(clock).format(FORMATO_HORA)
                + " (America/Lima); rol activo: " + usuario.getRolPrincipal() + ".\n"
                + "Mensaje del usuario: " + message;

        return chatClient.prompt()
                .user(contexto)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, username))
                .call()
                .entity(ChatResponse.class);
    }
}
