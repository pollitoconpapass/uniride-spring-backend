package com.uniride.repositories;

import com.uniride.entities.HorarioAcademico;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HorarioAcademicoRepository extends JpaRepository<HorarioAcademico, Long> {

    Optional<HorarioAcademico> findByUsuarioId(Long usuarioId);

    boolean existsByUsuarioId(Long usuarioId);
}
