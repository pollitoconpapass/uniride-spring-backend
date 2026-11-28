package com.uniride.agent.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void cargaElPromptComunYAnexaLosTemasDelCatalogo() {
        String prompt = AgentConfig.prompt(List.of(
                new AgentDefinition("viajes", "Buscar viajes disponibles."),
                new AgentDefinition("rutas", "Gestionar mis rutas.")));

        assertThat(prompt).contains("# Rol", "# Reglas generales", "Eres el asistente de UniRide");
        assertThat(prompt).contains("# Temas", "- viajes: Buscar viajes disponibles.", "- rutas: Gestionar mis rutas.");
    }

    @Test
    void sinAgentesDevuelveSoloElPromptComun() {
        assertThat(AgentConfig.prompt(List.of())).contains("# Rol").doesNotContain("# Temas");
    }
}
