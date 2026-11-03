package com.uniride.dto.responses;

/** Vista acotada del perfil que comparte el solicitante con el conductor. */
public record PerfilPasajeroUs14Respuesta(
        Long usuarioId,
        String nombre,
        String apellidos,
        String carrera,
        String distrito,
        String universidad,
        String gustos,
        String hobbies) {
}
