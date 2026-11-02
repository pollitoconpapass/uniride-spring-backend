package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.services.PerfilRolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(PerfilRolService.class)
class PerfilesRolRepositoryTest {

    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ConductorRepository conductorRepository;
    @Autowired private PasajeroRepository pasajeroRepository;
    @Autowired private PerfilRolService perfilRolService;

    private Usuario nuevoUsuario() {
        return usuarioRepository.saveAndFlush(Usuario.builder()
                .correoInstitucional("roles@upc.edu.pe")
                .contrasenaHash("hash-test")
                .nombre("Roles")
                .apellidos("Prueba")
                .telefono("999888777")
                .rolPrincipal(Rol.CONDUCTOR)
                .aceptaTerminos(true)
                .build());
    }

    @Test
    void conductorYPasajeroPuedenSerElMismoUsuario() {
        Usuario usuario = nuevoUsuario();
        perfilRolService.asegurarPerfil(usuario, Rol.CONDUCTOR);
        perfilRolService.asegurarPerfil(usuario, Rol.PASAJERO);
        conductorRepository.flush();
        pasajeroRepository.flush();

        assertThat(conductorRepository.findById(usuario.getId())).isPresent();
        assertThat(pasajeroRepository.findById(usuario.getId())).isPresent();
        assertThat(conductorRepository.findById(usuario.getId()).orElseThrow()
                .getUsuario().getId()).isEqualTo(usuario.getId());
    }

    @Test
    void cambiarRolNoEliminaPerfilAnteriorYNuncaDuplicaRegistros() {
        Usuario usuario = nuevoUsuario();
        perfilRolService.asegurarPerfil(usuario, Rol.CONDUCTOR);
        perfilRolService.asegurarPerfil(usuario, Rol.CONDUCTOR);
        usuario.setRolPrincipal(Rol.PASAJERO);
        perfilRolService.asegurarPerfil(usuario, Rol.PASAJERO);
        perfilRolService.asegurarPerfil(usuario, Rol.PASAJERO);
        conductorRepository.flush();
        pasajeroRepository.flush();

        assertThat(conductorRepository.count()).isEqualTo(1);
        assertThat(pasajeroRepository.count()).isEqualTo(1);
    }

    @Test
    void perfilDeRolNuloNoCreaRegistros() {
        Usuario usuario = nuevoUsuario();
        perfilRolService.asegurarPerfil(usuario, null);
        assertThat(conductorRepository.count()).isZero();
        assertThat(pasajeroRepository.count()).isZero();
    }
}
