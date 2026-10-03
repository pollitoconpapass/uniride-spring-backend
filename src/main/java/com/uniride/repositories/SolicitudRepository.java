package com.uniride.repositories;

import com.uniride.entities.Solicitud;
import com.uniride.enums.EstadoSolicitud;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolicitudRepository extends JpaRepository<Solicitud, Long> {

    long countByViajeIdAndEstado(Long viajeId, EstadoSolicitud estado);

    boolean existsByViajeIdAndPasajeroIdAndEstadoIn(Long viajeId, Long pasajeroId,
            Collection<EstadoSolicitud> estados);

    Optional<Solicitud> findByIdAndPasajeroId(Long id, Long pasajeroId);

    Page<Solicitud> findByPasajeroId(Long pasajeroId, Pageable pageable);

    @Query("SELECT s FROM Solicitud s WHERE s.pasajero.id = :pasajeroId AND s.estado = :estado "
            + "ORDER BY s.fechaCreacion DESC")
    Page<Solicitud> buscarPorPasajeroYEstado(@Param("pasajeroId") Long pasajeroId,
            @Param("estado") EstadoSolicitud estado, Pageable pageable);
}
