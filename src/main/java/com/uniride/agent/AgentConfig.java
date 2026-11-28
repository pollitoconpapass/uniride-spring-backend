package com.uniride.agent;

import com.uniride.enums.Rol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

@Configuration
public class AgentConfig {
    private static final String PROMPT = "agent/system-prompt.md";

    @Bean
    Instructions agentInstructions() {
        return load(new ClassPathResource(PROMPT));
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    static Instructions load(Resource resource) {
        String markdown;
        try {
            markdown = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException failure) {
            throw new IllegalStateException("No se pudo leer classpath:" + PROMPT, failure);
        }
        if (markdown.isBlank()) throw new IllegalStateException("El archivo classpath:" + PROMPT + " está vacío.");

        int conductor = markdown.indexOf("\n## Herramientas del conductor\n");
        int pasajero = markdown.indexOf("\n## Herramientas del pasajero\n");
        int compartidas = markdown.indexOf("\n## Herramientas compartidas\n");
        if (conductor < 0 || pasajero <= conductor || compartidas <= pasajero) {
            throw new IllegalStateException("Revisa el orden de las secciones de herramientas en classpath:" + PROMPT);
        }
        return new Instructions(markdown.substring(0, pasajero) + markdown.substring(compartidas),
                markdown.substring(0, conductor) + markdown.substring(pasajero));
    }

    record Instructions(String conductor, String pasajero) {
        String forRole(Rol role) {
            return switch (role) {
                case CONDUCTOR -> conductor;
                case PASAJERO -> pasajero;
            };
        }
    }
}
