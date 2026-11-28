package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniride.enums.Rol;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class AgentConfigTest {
    @Test void readsUtf8AndIncludesOnlyTheAuthenticatedRolesSection() {
        var instructions = new AgentConfig().agentInstructions();
        for (Rol role : Rol.values()) {
            String prompt = instructions.forRole(role);
            assertThat(prompt).contains("## Identidad y propósito", "## Contexto del usuario",
                    "## Selección de herramientas", "## Herramientas compartidas", "## Aclaraciones y datos faltantes",
                    "## Continuidad conversacional", "## Confirmaciones y cancelaciones", "## Seguridad",
                    "## Formato de respuesta", "## Ejemplos de selección de herramientas", "español");
        }
        assertThat(instructions.forRole(Rol.CONDUCTOR))
                .contains("## Herramientas del conductor").doesNotContain("## Herramientas del pasajero");
        assertThat(instructions.forRole(Rol.PASAJERO))
                .contains("## Herramientas del pasajero").doesNotContain("## Herramientas del conductor");
    }

    @Test void missingOrEmptyResourceFailsDuringConfiguration() {
        assertThatThrownBy(() -> AgentConfig.load(new ClassPathResource("agent/prompt-ausente.md")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("classpath:agent/system-prompt.md");
        assertThatThrownBy(() -> AgentConfig.load(new ByteArrayResource(" \r\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vacío");
    }
}
