package com.uniride.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class AgentConfig {

    private static final String PROMPT_COMUN = "prompts/common.md";

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, List<AgentDefinition> agentes) {
        return builder.defaultSystem(prompt(agentes)).build();
    }

    /** common.md + una línea por agente; la fecha y el rol se inyectan por turno, no aquí. */
    static String prompt(List<AgentDefinition> agentes) {
        String comun = leer(PROMPT_COMUN);
        if (agentes.isEmpty()) return comun;
        return comun + "\n\n# Temas\n" + agentes.stream()
                .map(agente -> "- " + agente.nombre() + ": " + agente.descripcion())
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String leer(String ruta) {
        try {
            return new ClassPathResource(ruta).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar " + ruta, e);
        }
    }
}
