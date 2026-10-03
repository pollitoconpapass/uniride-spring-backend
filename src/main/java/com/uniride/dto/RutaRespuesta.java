package com.uniride.dto;

import com.uniride.enums.EstadoRuta;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RutaRespuesta {

    private Long id;
    private String origen;
    private String destino;
    private String puntosReferencia;
    private String zonasSinParada;
    private String notaContribucion;
    private int capacidadMaxima;
    private EstadoRuta estado;
    private LocalDateTime fechaCreacion;
    private Long conductorId;
    private String conductorNombre;
}
