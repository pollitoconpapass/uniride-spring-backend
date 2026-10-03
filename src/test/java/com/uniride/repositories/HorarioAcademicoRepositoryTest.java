package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class HorarioAcademicoRepositoryTest {

    @Autowired
    private HorarioAcademicoRepository horarioAcademicoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario(String correo) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Maria")
                .apellidos("Lopez")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.PASAJERO)
                .aceptaTerminos(true)
                .build());
    }

    @Test
    void guardarYBuscarHorarioPorUsuario() {
        Usuario usuario = crearUsuario("horario1@upc.edu.pe");
        horarioAcademicoRepository.save(HorarioAcademico.builder().usuario(usuario).build());

        Optional<HorarioAcademico> encontrado = horarioAcademicoRepository.findByUsuarioId(usuario.getId());

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getFechaActualizacion()).isNotNull();
    }

    @Test
    void existeHorarioPorUsuario() {
        Usuario usuario = crearUsuario("horario2@upc.edu.pe");

        assertThat(horarioAcademicoRepository.existsByUsuarioId(usuario.getId())).isFalse();

        horarioAcademicoRepository.save(HorarioAcademico.builder().usuario(usuario).build());

        assertThat(horarioAcademicoRepository.existsByUsuarioId(usuario.getId())).isTrue();
    }

    @Test
    void buscarHorarioDeUsuarioSinHorario() {
        Usuario usuario = crearUsuario("horario3@upc.edu.pe");

        Optional<HorarioAcademico> encontrado = horarioAcademicoRepository.findByUsuarioId(usuario.getId());

        assertThat(encontrado).isEmpty();
    }
}
