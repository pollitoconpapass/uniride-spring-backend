package com.uniride.agent.dto;

import com.uniride.dto.responses.PerfilRespuesta;

public record PerfilAgenteRespuesta(
        boolean completo,
        String mensaje,
        PerfilRespuesta perfil) {
}
