package com.uniride.repositories;

import com.uniride.entities.Ruta;
import com.uniride.enums.EstadoRuta;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RutaRepository extends JpaRepository<Ruta, Long> {

    Page<Ruta> findByConductorId(Long conductorId, Pageable pageable);

    Optional<Ruta> findByIdAndConductorId(Long id, Long conductorId);

    boolean existsByConductorId(Long conductorId);

    @Query("SELECT r FROM Ruta r WHERE r.conductor.id = :conductorId AND r.estado = :estado")
    Page<Ruta> buscarPorConductorYEstado(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoRuta estado, Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM rutas WHERE conductor_id = :conductorId AND estado = :estado",
            nativeQuery = true)
    long contarPorConductorYEstado(@Param("conductorId") Long conductorId, @Param("estado") String estado);
}
