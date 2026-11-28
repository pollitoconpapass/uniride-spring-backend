package com.uniride.agent;

import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.conversation.Aclaraciones.EnvioAclaracion;
import com.uniride.agent.support.AgentProviderException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.exceptions.CamposInvalidosException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvioAclaracionTest {
    @Test void validatesOnlyTheCurrentFormAndItsPromptedFields() {
        var esquema = Aclaraciones.crear("crearRuta", Campo.CAPACIDAD.obligatorio()).conId(UUID.randomUUID());
        String prefijo = "Datos de aclaración " + esquema.id() + ": ";
        assertThat(EnvioAclaracion.leer(prefijo + "{\"capacidad\":3}", esquema)).isEqualTo(Map.of("capacidad", "3"));
        assertThatThrownBy(() -> EnvioAclaracion.leer(prefijo + "{\"capacidad\":3}", null))
                .isInstanceOf(AgentProviderException.class);
        for (String datos : new String[]{"{\"origen\":\"Surco\"}", "{\"capacidad\":0}",
                "{\"capacidad\":3,\"capacidad\":4}", "{\"capacidad\":3} {\"capacidad\":4}"}) {
            assertThatThrownBy(() -> EnvioAclaracion.leer(prefijo + datos, esquema))
                    .isInstanceOf(CamposInvalidosException.class);
        }
    }

    @Test void descriptorRejectsExecutableContentAndFieldsOfOtherOperations() {
        assertThatThrownBy(() -> new OpcionAclaracion("https://ejemplo.invalid", "Ruta"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpcionAclaracion("Surco", "<script>malicioso</script>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aclaraciones.crear("crearRuta", Campo.PDF_TIPO.obligatorio()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
