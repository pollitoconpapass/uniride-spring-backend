package com.uniride.entities;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity @Table(name = "correo_outbox")
@Getter @Setter @NoArgsConstructor
public class CorreoPendiente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private Long notificacionId;
    @Column(nullable = false) private String destino;
    @Column(nullable = false) private String asunto;
    @Column(nullable = false, columnDefinition = "text") private String contenidoCifrado;
    @Column(nullable = false) private int intentos;
    @Column(nullable = false) private Instant siguienteIntento;
    private Instant enviado;
    private Instant descartado;
    private Instant expira;
    private Long otpUsuarioId;
    private String otpHash;
}
