package com.uniride.dto.requests;

import com.uniride.enums.Rol;
import jakarta.validation.constraints.NotNull;

public record CambiarRolRequest(
        @NotNull(message = "Debes seleccionar tu rol principal (conductor o pasajero)")
        Rol rol) {
}
