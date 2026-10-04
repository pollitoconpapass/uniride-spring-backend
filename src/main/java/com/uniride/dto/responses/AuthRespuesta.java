package com.uniride.dto.responses;

public record AuthRespuesta(
        String token,
        String mensaje,
        UsuarioRespuesta usuario) {
}
