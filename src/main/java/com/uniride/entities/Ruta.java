package com.uniride.entities;

import com.uniride.enums.EstadoRuta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "rutas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ruta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conductor_id", nullable = false)
    private Usuario conductor;

    @Column(nullable = false)
    private String origen;

    @Column(nullable = false)
    private String destino;

    @Column(name = "capacidad_maxima", nullable = false)
    private int capacidadMaxima;

    @Column(name = "puntos_referencia", nullable = false, columnDefinition = "text")
    private String puntosReferencia;

    @Column(name = "zonas_sin_parada", columnDefinition = "text")
    private String zonasSinParada;

    @Column(name = "nota_contribucion")
    private String notaContribucion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoRuta estado;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    void prePersist() {
        if (estado == null) {
            estado = EstadoRuta.ACTIVA;
        }
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
    }
}
