package com.uniride.repositories;

import com.uniride.entities.Ruta;
import com.uniride.enums.EstadoRuta;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RutaRepository extends JpaRepository<Ruta, Long> {

    /** Agent reads must not call the legacy state synchronization method. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"conductor"})
    @Query("SELECT r FROM Ruta r WHERE r.conductor.id = :conductorId "
            + "AND r.estado <> :eliminada "
            + "AND LOCATE(COALESCE(:origen, ''), LOWER(r.origen)) > 0 "
            + "AND LOCATE(COALESCE(:destino, ''), LOWER(r.destino)) > 0 "
            + "ORDER BY r.fechaCreacion DESC, r.id DESC")
    Page<Ruta> consultarRutasAgente(@Param("conductorId") Long conductorId,
            @Param("eliminada") EstadoRuta eliminada, @Param("origen") String origen,
            @Param("destino") String destino, Pageable pageable);

    Page<Ruta> findByConductorId(Long conductorId, Pageable pageable);

    // US09: obtener todas las rutas del conductor para recalcular estados antes de filtrar.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Ruta r WHERE r.conductor.id = :conductorId ORDER BY r.id")
    List<Ruta> findByConductorId(Long conductorId);

    Page<Ruta> findByConductorIdAndEstadoNot(Long conductorId, EstadoRuta estado, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Ruta> findByIdAndConductorId(Long id, Long conductorId);

    boolean existsByConductorId(Long conductorId);

    @Query("SELECT r FROM Ruta r WHERE r.conductor.id = :conductorId AND r.estado = :estado")
    Page<Ruta> buscarPorConductorYEstado(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoRuta estado, Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM rutas WHERE conductor_id = :conductorId AND estado = :estado",
            nativeQuery = true)
    long contarPorConductorYEstado(@Param("conductorId") Long conductorId, @Param("estado") String estado);
}
