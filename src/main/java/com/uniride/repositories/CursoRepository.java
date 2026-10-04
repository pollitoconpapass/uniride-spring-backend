package com.uniride.repositories;

import com.uniride.entities.Curso;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CursoRepository extends JpaRepository<Curso, Long> {

    List<Curso> findByHorarioAcademicoId(Long horarioAcademicoId);

    Page<Curso> findByHorarioAcademicoId(Long horarioAcademicoId, Pageable pageable);

    long countByHorarioAcademicoId(Long horarioAcademicoId);

    boolean existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(Long horarioAcademicoId,
            String nombre, String dia, LocalTime horaInicio);

    @Query("SELECT c FROM Curso c WHERE c.horarioAcademico.usuario.id = :usuarioId")
    Page<Curso> buscarPorUsuario(@Param("usuarioId") Long usuarioId, Pageable pageable);

    @Query("SELECT c FROM Curso c WHERE c.horarioAcademico.id = :horarioId "
            + "AND LOWER(c.dia) = LOWER(:dia) "
            + "AND c.horaInicio < :horaFin AND c.horaFin > :horaInicio")
    List<Curso> buscarConflictos(@Param("horarioId") Long horarioId,
            @Param("dia") String dia,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFin") LocalTime horaFin);

    @Query(value = "SELECT c.* FROM cursos c "
            + "INNER JOIN horario_academicos h ON c.horario_academico_id = h.id "
            + "WHERE h.usuario_id = :usuarioId AND LOWER(c.dia) = LOWER(:dia)",
            nativeQuery = true)
    List<Curso> buscarPorUsuarioYDia(@Param("usuarioId") Long usuarioId, @Param("dia") String dia);
}
