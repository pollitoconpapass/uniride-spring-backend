package com.uniride.dto.responses;

import com.uniride.enums.Rol;

public record UsuarioRespuesta(
        Long id,
        String correo,
        String nombre,
        String apellidos,
        String telefono,
        Rol rol,
        boolean cuentaVerificada) {
}
