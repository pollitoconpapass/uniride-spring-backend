package com.uniride.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "archivo_cargas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchivoCarga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "horario_academico_id", nullable = false)
    private HorarioAcademico horarioAcademico;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "formato", nullable = false)
    private String formato;

    @Column(name = "tamano_mb", nullable = false)
    private double tamanoMb;

    @Column(name = "fecha_subida", nullable = false)
    private LocalDateTime fechaSubida;

    @PrePersist
    void antesDeGuardar() {
        if (fechaSubida == null) {
            fechaSubida = LocalDateTime.now(ZoneId.systemDefault());
        }
    }
}
