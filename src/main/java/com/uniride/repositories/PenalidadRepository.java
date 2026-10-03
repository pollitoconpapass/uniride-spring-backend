package com.uniride.repositories;

import com.uniride.entities.Penalidad;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenalidadRepository extends JpaRepository<Penalidad, Long> {

    long countByUsuarioId(Long usuarioId);

    boolean existsByViajeIdAndUsuarioId(Long viajeId, Long usuarioId);

    List<Penalidad> findByViajeId(Long viajeId);
}
