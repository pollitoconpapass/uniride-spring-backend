package com.uniride.repositories;

import com.uniride.entities.Solicitud;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import java.util.Collection;
import java.util.List;
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

    Optional<Solicitud> findByIdAndViajeRutaConductorId(Long id, Long conductorId);

    Page<Solicitud> findByPasajeroId(Long pasajeroId, Pageable pageable);

    Page<Solicitud> findByViajeIdOrderByFechaCreacionDesc(Long viajeId, Pageable pageable);

    List<Solicitud> findByViajeIdAndEstadoIn(Long viajeId, Collection<EstadoSolicitud> estados);

    @Query("SELECT s FROM Solicitud s WHERE s.pasajero.id = :pasajeroId AND s.estado = :estado "
            + "ORDER BY s.fechaCreacion DESC")
    Page<Solicitud> buscarPorPasajeroYEstado(@Param("pasajeroId") Long pasajeroId,
            @Param("estado") EstadoSolicitud estado, Pageable pageable);

    @Query("SELECT s FROM Solicitud s WHERE s.viaje.id = :viajeId AND s.estado = :estado "
            + "ORDER BY s.fechaCreacion DESC")
    Page<Solicitud> buscarPorViajeYEstado(@Param("viajeId") Long viajeId,
            @Param("estado") EstadoSolicitud estado, Pageable pageable);

    @Query("SELECT COUNT(s) FROM Solicitud s "
            + "WHERE s.pasajero.id = :pasajeroId AND s.estado = :aceptada "
            + "AND s.viaje.estado = :estadoViaje")
    long contarRealizadosComoPasajero(@Param("pasajeroId") Long pasajeroId,
            @Param("aceptada") EstadoSolicitud aceptada,
            @Param("estadoViaje") EstadoViaje estadoViaje);

    @Query("SELECT s.viaje.dia, COUNT(s) FROM Solicitud s "
            + "WHERE s.pasajero.id = :pasajeroId AND s.estado = :aceptada "
            + "AND s.viaje.estado = :estadoViaje GROUP BY s.viaje.dia")
    List<Object[]> frecuenciaPorDiaComoPasajero(@Param("pasajeroId") Long pasajeroId,
            @Param("aceptada") EstadoSolicitud aceptada,
            @Param("estadoViaje") EstadoViaje estadoViaje);

    @Query("SELECT COUNT(DISTINCT s.viaje.id) FROM Solicitud s "
            + "WHERE s.pasajero.id = :pasajeroId AND s.viaje.estado = :estadoViaje")
    long contarViajesCanceladosComoPasajero(@Param("pasajeroId") Long pasajeroId,
            @Param("estadoViaje") EstadoViaje estadoViaje);

    @Query("SELECT COUNT(DISTINCT s.viaje.id) FROM Solicitud s "
            + "WHERE s.pasajero.id = :pasajeroId AND s.viaje.estado = :estadoViaje "
            + "AND NOT EXISTS (SELECT p FROM Penalidad p "
            + "WHERE p.viaje.id = s.viaje.id AND p.usuario.id = :pasajeroId)")
    long contarViajesCanceladosPorTerceros(@Param("pasajeroId") Long pasajeroId,
            @Param("estadoViaje") EstadoViaje estadoViaje);

    @Query("SELECT s FROM Solicitud s WHERE s.pasajero.id = :pasajeroId "
            + "AND s.estado IN :estados ORDER BY s.viaje.fecha DESC, s.viaje.hora DESC")
    List<Solicitud> historialComoPasajero(@Param("pasajeroId") Long pasajeroId,
            @Param("estados") Collection<EstadoSolicitud> estados);
}
