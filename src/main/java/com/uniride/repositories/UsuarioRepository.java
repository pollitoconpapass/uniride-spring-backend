package com.uniride.repositories;

import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByCorreoInstitucional(String correoInstitucional);

    boolean existsByCorreoInstitucional(String correoInstitucional);

    boolean existsByTelefono(String telefono);

    @Query("SELECT u FROM Usuario u WHERE u.rolPrincipal = :rol")
    List<Usuario> buscarPorRol(@Param("rol") Rol rol);
}
