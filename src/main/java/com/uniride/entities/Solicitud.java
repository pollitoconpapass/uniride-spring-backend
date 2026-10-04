package com.uniride.entities;

import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
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
import jakarta.persistence.OneToOne;
import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "solicitudes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Solicitud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "viaje_id", nullable = false)
    private Viaje viaje;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pasajero_id", nullable = false)
    private Usuario pasajero;

    @Column(columnDefinition = "text")
    private String mensaje;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferencia_compensacion", nullable = false)
    private TipoCompensacion preferenciaCompensacion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoSolicitud estado;

    @Column(name = "motivo_rechazo")
    private String motivoRechazo;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metodo_compensacion_id")
    private MetodoCompensacion metodoCompensacion;

    @OneToOne(mappedBy = "solicitud", fetch = FetchType.LAZY)
    private AcuerdoCompensacion acuerdo;

    @PrePersist
    void prePersist() {
        if (estado == null) {
            estado = EstadoSolicitud.PENDIENTE;
        }
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now(ZoneId.systemDefault());
        }
    }
}
