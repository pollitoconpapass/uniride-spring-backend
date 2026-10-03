package com.uniride.repositories;

import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViajeRepository extends JpaRepository<Viaje, Long> {

    Page<Viaje> findByRutaIdOrderByFechaAscHoraAsc(Long rutaId, Pageable pageable);

    Optional<Viaje> findByIdAndRutaConductorId(Long id, Long conductorId);

    boolean existsByRutaId(Long rutaId);

    @Query("SELECT v FROM Viaje v JOIN v.ruta r "
            + "WHERE v.fecha >= CURRENT_DATE AND v.estado = :estado "
            + "AND LOWER(r.origen) = COALESCE(:origen, LOWER(r.origen)) "
            + "AND LOWER(r.destino) = COALESCE(:destino, LOWER(r.destino)) "
            + "AND LOWER(v.dia) = COALESCE(:dia, LOWER(v.dia)) "
            + "AND v.hora = COALESCE(:hora, v.hora) "
            + "AND LOWER(r.origen) LIKE COALESCE(:distrito, '%') "
            + "ORDER BY v.fecha ASC, v.hora ASC")
    Page<Viaje> buscarViajes(@Param("origen") String origen, @Param("destino") String destino,
            @Param("dia") String dia, @Param("hora") LocalTime hora,
            @Param("distrito") String distrito, @Param("estado") EstadoViaje estado,
            Pageable pageable);

    @Query("SELECT v FROM Viaje v WHERE v.ruta.conductor.id = :conductorId "
            + "AND v.confirmado = false AND v.estado = :estado "
            + "AND v.fecha BETWEEN :desde AND :hasta")
    List<Viaje> buscarPendientesDeConfirmacion(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoViaje estado, @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);
}
