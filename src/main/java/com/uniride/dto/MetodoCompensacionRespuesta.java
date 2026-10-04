package com.uniride.dto;

import com.uniride.enums.TipoCompensacion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetodoCompensacionRespuesta {

    private Long id;
    private TipoCompensacion tipo;
    private String descripcion;
    private boolean activo;
}
