package com.uniride.repositories;

import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    @Query("SELECT u FROM Usuario u WHERE LOWER(TRIM(u.correoInstitucional)) = LOWER(TRIM(:correoInstitucional))")
    Optional<Usuario> findByCorreoInstitucional(@Param("correoInstitucional") String correoInstitucional);

    @Query("SELECT COUNT(u) > 0 FROM Usuario u WHERE LOWER(TRIM(u.correoInstitucional)) = LOWER(TRIM(:correoInstitucional))")
    boolean existsByCorreoInstitucional(@Param("correoInstitucional") String correoInstitucional);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM Usuario u WHERE LOWER(TRIM(u.correoInstitucional)) = :correo")
    Optional<Usuario> bloquearPorCorreo(@Param("correo") String correo);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM Usuario u WHERE u.id = :id")
    Optional<Usuario> bloquearOtpParaEntrega(@Param("id") Long id);

    boolean existsByTelefono(String telefono);

    @Query("SELECT u FROM Usuario u WHERE u.rolPrincipal = :rol")
    List<Usuario> buscarPorRol(@Param("rol") Rol rol);
}
