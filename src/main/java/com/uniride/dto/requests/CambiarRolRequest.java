package com.uniride.dto.requests;

import com.uniride.enums.Rol;
import jakarta.validation.constraints.NotNull;

public record CambiarRolRequest(
        @NotNull(message = "Debes seleccionar un rol activo (conductor o pasajero)")
        Rol rol) {
}
