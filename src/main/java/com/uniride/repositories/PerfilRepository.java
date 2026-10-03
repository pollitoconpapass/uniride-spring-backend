package com.uniride.repositories;

import com.uniride.entities.Perfil;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerfilRepository extends JpaRepository<Perfil, Long> {

    Optional<Perfil> findByUsuarioId(Long usuarioId);

    boolean existsByUsuarioId(Long usuarioId);

    @Query("SELECT p FROM Perfil p WHERE p.distrito = :distrito")
    List<Perfil> buscarPorDistrito(@Param("distrito") String distrito);
}
