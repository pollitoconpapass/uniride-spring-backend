package com.uniride.dto;

import com.uniride.enums.EstadoAcuerdo;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
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
public class SolicitudRespuesta {

    private Long id;
    private Long viajeId;
    private LocalDate fecha;
    private LocalTime hora;
    private String dia;
    private Long rutaId;
    private String origen;
    private String destino;
    private String mensaje;
    private TipoCompensacion preferenciaCompensacion;
    private EstadoSolicitud estado;
    private String motivoRechazo;
    private LocalDateTime fechaCreacion;
    private String advertencia;
    private Long pasajeroId;
    private String pasajeroNombre;
    private String pasajeroCorreo;
    private String pasajeroCarrera;
    private String pasajeroDistrito;
    private String acuerdoTerminos;
    private EstadoAcuerdo acuerdoEstado;
}
