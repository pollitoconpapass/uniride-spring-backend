package com.uniride.repositories;

import com.uniride.entities.Viaje;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViajeRepository extends JpaRepository<Viaje, Long> {

    Page<Viaje> findByRutaIdOrderByFechaAscHoraAsc(Long rutaId, Pageable pageable);

    Optional<Viaje> findByIdAndRutaConductorId(Long id, Long conductorId);

    boolean existsByRutaId(Long rutaId);
}
