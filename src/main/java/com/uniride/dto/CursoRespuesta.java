package com.uniride.dto;

import java.time.LocalTime;
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
public class CursoRespuesta {

    private Long id;
    private String nombre;
    private String dia;
    private LocalTime horaInicio;
    private LocalTime horaFin;
}
