package com.uniride.entities;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "perfiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Perfil {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "apellidos", nullable = false)
    private String apellidos;

    @Column(name = "carrera", nullable = false)
    private String carrera;

    @Column(name = "distrito", nullable = false)
    private String distrito;

    @Column(name = "universidad", nullable = false)
    private String universidad;

    @Column(name = "dias_disponibles", nullable = false)
    private String diasDisponibles;

    @Column(name = "horarios_disponibles", nullable = false)
    private String horariosDisponibles;

    @Column(name = "fecha_inicio_clases", nullable = false)
    private LocalDate fechaInicioClases;

    @Column(name = "fecha_fin_clases", nullable = false)
    private LocalDate fechaFinClases;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_compensacion_favorito")
    private TipoCompensacion metodoCompensacionFavorito;

    @Column(name = "gustos", columnDefinition = "text")
    private String gustos;

    @Column(name = "hobbies", columnDefinition = "text")
    private String hobbies;

    @Column(name = "datos_curiosos", columnDefinition = "text")
    private String datosCuriosos;

    @Column(name = "fecha_actualizacion")
    private LocalDateTime fechaActualizacion;

    @PrePersist
    void antesDeGuardar() {
        fechaActualizacion = LocalDateTime.now();
    }

    @PreUpdate
    void antesDeActualizar() {
        fechaActualizacion = LocalDateTime.now();
    }
}
