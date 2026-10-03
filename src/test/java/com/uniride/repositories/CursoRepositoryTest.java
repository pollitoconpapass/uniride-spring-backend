package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Curso;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class CursoRepositoryTest {

    @Autowired
    private CursoRepository cursoRepository;

    @Autowired
    private HorarioAcademicoRepository horarioAcademicoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private HorarioAcademico crearHorario(String correo) {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Luis")
                .apellidos("Torres")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.PASAJERO)
                .aceptaTerminos(true)
                .build());
        return horarioAcademicoRepository.save(HorarioAcademico.builder().usuario(usuario).build());
    }

    private Curso crearCurso(HorarioAcademico horario, String nombre, String dia,
            String inicio, String fin) {
        return Curso.builder()
                .horarioAcademico(horario)
                .nombre(nombre)
                .dia(dia)
                .horaInicio(LocalTime.parse(inicio))
                .horaFin(LocalTime.parse(fin))
                .build();
    }

    @Test
    void guardarYBuscarCursosPorHorario() {
        HorarioAcademico horario = crearHorario("cursos1@upc.edu.pe");
        cursoRepository.save(crearCurso(horario, "Calculo II", "Lunes", "08:00", "10:00"));
        cursoRepository.save(crearCurso(horario, "Fisica I", "Miercoles", "10:00", "12:00"));

        List<Curso> cursos = cursoRepository.findByHorarioAcademicoId(horario.getId());

        assertThat(cursos).hasSize(2);
        assertThat(cursoRepository.countByHorarioAcademicoId(horario.getId())).isEqualTo(2);
    }

    @Test
    void buscarCursosPaginadosPorUsuarioConJpql() {
        HorarioAcademico horario = crearHorario("cursos2@upc.edu.pe");
        cursoRepository.save(crearCurso(horario, "Base de Datos", "Martes", "08:00", "10:00"));
        cursoRepository.save(crearCurso(horario, "Sistemas Operativos", "Jueves", "14:00", "16:00"));
        cursoRepository.save(crearCurso(horario, "Ingles", "Viernes", "16:00", "18:00"));

        Long usuarioId = horario.getUsuario().getId();
        Page<Curso> pagina = cursoRepository.buscarPorUsuario(usuarioId, PageRequest.of(0, 2));

        assertThat(pagina.getContent()).hasSize(2);
        assertThat(pagina.getTotalElements()).isEqualTo(3);
    }

    @Test
    void buscarCursosPorUsuarioYDiaConSqlNativo() {
        HorarioAcademico horario = crearHorario("cursos3@upc.edu.pe");
        cursoRepository.save(crearCurso(horario, "Calculo II", "Lunes", "08:00", "10:00"));
        cursoRepository.save(crearCurso(horario, "Fisica I", "Lunes", "10:00", "12:00"));
        cursoRepository.save(crearCurso(horario, "Ingles", "Viernes", "16:00", "18:00"));

        Long usuarioId = horario.getUsuario().getId();
        List<Curso> delLunes = cursoRepository.buscarPorUsuarioYDia(usuarioId, "lunes");

        assertThat(delLunes).hasSize(2);
        assertThat(delLunes).allSatisfy(curso -> assertThat(curso.getDia()).isEqualTo("Lunes"));
    }

    @Test
    void buscarCursosDeHorarioSinCursos() {
        HorarioAcademico horario = crearHorario("cursos4@upc.edu.pe");

        List<Curso> cursos = cursoRepository.findByHorarioAcademicoId(horario.getId());

        assertThat(cursos).isEmpty();
    }
}
