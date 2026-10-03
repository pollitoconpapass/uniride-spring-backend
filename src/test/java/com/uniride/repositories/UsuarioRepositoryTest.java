package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class UsuarioRepositoryTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario(String correo, String telefono, Rol rol) {
        return Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Juan")
                .apellidos("Perez")
                .telefono(telefono)
                .rolPrincipal(rol)
                .aceptaTerminos(true)
                .build();
    }

    @Test
    void guardarYBuscarPorCorreoInstitucional() {
        usuarioRepository.save(crearUsuario("juan.perez@upc.edu.pe", "999888777", Rol.PASAJERO));

        Optional<Usuario> encontrado = usuarioRepository.findByCorreoInstitucional("juan.perez@upc.edu.pe");

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getNombre()).isEqualTo("Juan");
        assertThat(encontrado.get().getFechaRegistro()).isNotNull();
        assertThat(encontrado.get().isCuentaVerificada()).isFalse();
    }

    @Test
    void existeCorreoInstitucional() {
        usuarioRepository.save(crearUsuario("ana.garcia@upc.edu.pe", "911222333", Rol.CONDUCTOR));

        assertThat(usuarioRepository.existsByCorreoInstitucional("ana.garcia@upc.edu.pe")).isTrue();
        assertThat(usuarioRepository.existsByCorreoInstitucional("no.existe@upc.edu.pe")).isFalse();
    }

    @Test
    void existeTelefono() {
        usuarioRepository.save(crearUsuario("luis.torres@upc.edu.pe", "955444333", Rol.PASAJERO));

        assertThat(usuarioRepository.existsByTelefono("955444333")).isTrue();
        assertThat(usuarioRepository.existsByTelefono("900000000")).isFalse();
    }

    @Test
    void buscarPorRolConJpql() {
        usuarioRepository.save(crearUsuario("conductor1@upc.edu.pe", "900111222", Rol.CONDUCTOR));
        usuarioRepository.save(crearUsuario("conductor2@upc.edu.pe", "900333444", Rol.CONDUCTOR));
        usuarioRepository.save(crearUsuario("pasajero1@upc.edu.pe", "900555666", Rol.PASAJERO));

        List<Usuario> conductores = usuarioRepository.buscarPorRol(Rol.CONDUCTOR);
        List<Usuario> pasajeros = usuarioRepository.buscarPorRol(Rol.PASAJERO);

        assertThat(conductores).hasSize(2);
        assertThat(pasajeros).hasSize(1);
    }

    @Test
    void correoDuplicadoLanzaExcepcion() {
        usuarioRepository.save(crearUsuario("duplicado@upc.edu.pe", "977888999", Rol.PASAJERO));

        assertThatThrownBy(() -> usuarioRepository
                .saveAndFlush(crearUsuario("duplicado@upc.edu.pe", "977000111", Rol.PASAJERO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void buscarPorCorreoInexistenteVacio() {
        Optional<Usuario> encontrado = usuarioRepository.findByCorreoInstitucional("nadie@upc.edu.pe");

        assertThat(encontrado).isEmpty();
    }
}
