package com.uniride.dto;

import com.uniride.enums.Rol;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CambiarRolRequest {

    @NotNull(message = "Debes seleccionar tu rol principal (conductor o pasajero)")
    private Rol rol;
}
