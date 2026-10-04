package com.uniride.dto.requests;

import com.uniride.enums.Rol;
import com.uniride.util.ValidacionUtil;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegistroRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Pattern(regexp = ValidacionUtil.CORREO_REGEX,
                message = "El correo debe ser institucional y contener .edu después del @")
        String correo,
        @NotBlank(message = "La contraseña es obligatoria")
        @Pattern(regexp = ValidacionUtil.CONTRASENA_REGEX,
                message = "La contraseña debe tener mínimo 8 caracteres, una mayúscula, una minúscula y un número o símbolo")
        String contrasena,
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,
        @NotBlank(message = "Los apellidos son obligatorios")
        String apellidos,
        @NotBlank(message = "El teléfono es obligatorio")
        @Pattern(regexp = ValidacionUtil.TELEFONO_REGEX, message = "El teléfono no tiene un formato válido")
        String telefono,
        @NotNull(message = "Debes seleccionar tu rol principal (conductor o pasajero)")
        Rol rol,
        @AssertTrue(message = "Debes aceptar los términos y condiciones")
        boolean aceptaTerminos) {
}
