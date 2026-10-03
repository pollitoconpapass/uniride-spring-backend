package com.uniride.repositories;

import com.uniride.entities.ArchivoCarga;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchivoCargaRepository extends JpaRepository<ArchivoCarga, Long> {

    List<ArchivoCarga> findByHorarioAcademicoIdOrderByFechaSubidaDesc(Long horarioAcademicoId);

    long countByHorarioAcademicoId(Long horarioAcademicoId);
}
