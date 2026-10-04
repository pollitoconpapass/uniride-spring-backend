package com.uniride.dto.responses;

import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PerfilRespuesta(
        Long id,
        String nombre,
        String apellidos,
        String carrera,
        String distrito,
        String universidad,
        String diasDisponibles,
        String horariosDisponibles,
        LocalDate fechaInicioClases,
        LocalDate fechaFinClases,
        TipoCompensacion metodoCompensacionFavorito,
        String gustos,
        String hobbies,
        String datosCuriosos,
        LocalDateTime fechaActualizacion) {
}
