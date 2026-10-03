package com.uniride.dto;

import com.uniride.enums.Rol;
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
public class UsuarioRespuesta {

    private Long id;
    private String correo;
    private String nombre;
    private String apellidos;
    private String telefono;
    private Rol rol;
    private boolean cuentaVerificada;
}
