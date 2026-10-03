package com.uniride.dto;

import com.uniride.enums.EstadoViaje;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViajeRespuesta {

    private Long id;
    private Long rutaId;
    private LocalDate fecha;
    private LocalTime hora;
    private String dia;
    private boolean confirmado;
    private LocalDateTime fechaConfirmacion;
    private EstadoViaje estado;
    private String mensaje;
}
