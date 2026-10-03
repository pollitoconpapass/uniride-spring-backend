package com.uniride.dto;

import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class PerfilRespuesta {

    private Long id;
    private String nombre;
    private String carrera;
    private String distrito;
    private String universidad;
    private String diasDisponibles;
    private String horariosDisponibles;
    private LocalDate fechaInicioClases;
    private LocalDate fechaFinClases;
    private TipoCompensacion metodoCompensacionFavorito;
    private String gustos;
    private String hobbies;
    private String datosCuriosos;
    private LocalDateTime fechaActualizacion;
}
