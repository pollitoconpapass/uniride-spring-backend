package com.uniride.agent.tools;

import com.uniride.agent.tools.shared.HorarioTools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.CursoResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.dto.requests.CursoRequest;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.entities.Curso;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.repositories.CursoRepository;
import com.uniride.services.HorarioAcademicoService;
import com.uniride.services.UsuarioService;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class HorarioAcademicoAgentToolsTest {

    @Mock HorarioAcademicoService horarioService;
    @Mock CursoRepository cursoRepository;
    @Mock UsuarioService usuarioService;
    @Mock ConfirmationService confirmaciones;
    private HorarioTools tools;
    private Usuario usuario;

    @BeforeEach
    void setup() {
        usuario = Usuario.builder().id(11L).build();
        tools = new HorarioTools(horarioService, cursoRepository, usuarioService, confirmaciones);
    }

    @Test
    void consultarMisHorariosListaTodosOFiltraPorDiaSinTildes() {
        when(horarioService.listarCursos(PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(
                        new CursoRespuesta(1L, "Algoritmos", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0))),
                        PageRequest.of(0, 10), 1));
        var todos = tools.consultarMisHorarios(null, null);
        assertThat(todos.items()).containsExactly(
                new CursoResumen(1L, "Algoritmos", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0)));

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.buscarPorUsuario(11L, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(
                        curso(1L, "Algoritmos", "Lunes", 8, 10),
                        curso(2L, "Base de Datos", "Martes", 14, 16),
                        curso(3L, "Ingeniería de Software", "Miércoles", 9, 11))));

        var miercoles = tools.consultarMisHorarios("miercoles", 0);
        assertThat(miercoles.items()).extracting(CursoResumen::nombre).containsExactly("Ingeniería de Software");
        assertThatThrownBy(() -> tools.consultarMisHorarios("feriado", 0)).isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void registrarCursoValidaDatosYPreparaConfirmacion() {
        assertThat(tools.registrarCurso("Algoritmos", "Lunes", LocalTime.of(8, 0), null).estado())
                .isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        var horarioInvalido = new CursoRequest("Algoritmos", "Lunes", LocalTime.of(10, 0), LocalTime.of(8, 0));
        when(confirmaciones.prepararRegistrarCurso(horarioInvalido))
                .thenThrow(new CamposInvalidosException("La hora de fin debe ser posterior a la hora de inicio"));
        var correccion = tools.registrarCurso("Algoritmos", "Lunes", LocalTime.of(10, 0), LocalTime.of(8, 0));
        assertThat(correccion.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(correccion.aclaracion().alMenosUno()).containsExactly("horaInicio", "horaFin");

        var expected = new CursoRequest("Algoritmos", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma curso.");
        when(confirmaciones.prepararRegistrarCurso(expected)).thenReturn(pendiente);

        assertThat(tools.registrarCurso("Algoritmos", "lunes", LocalTime.of(8, 0), LocalTime.of(10, 0)))
                .isSameAs(pendiente);

        when(confirmaciones.prepararRegistrarCurso(expected))
                .thenThrow(new BusinessException("Conflicto de horario"));
        assertThat(tools.registrarCurso("Algoritmos", "lunes", LocalTime.of(8, 0), LocalTime.of(10, 0)).estado())
                .isEqualTo(EstadoOperacion.ERROR);
    }

    @Test
    void eliminarCursoResuelveUnicoAmbiguoONoEncontrado() {
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.buscarPorUsuario(11L, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(
                        curso(1L, "Matemáticas", "Lunes", 8, 10),
                        curso(2L, "Matemáticas", "Miércoles", 8, 10),
                        curso(3L, "Algoritmos", "Martes", 10, 12))));

        assertThat(tools.eliminarCurso(null, null, null).estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(tools.eliminarCurso("Física", null, null).estado()).isEqualTo(EstadoOperacion.NO_ENCONTRADO);
        assertThat(tools.eliminarCurso("Matematicas", null, null).estado()).isEqualTo(EstadoOperacion.AMBIGUO);

        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma eliminación.");
        when(confirmaciones.prepararEliminarCurso(2L)).thenReturn(pendiente);

        assertThat(tools.eliminarCurso("Matematicas", "miercoles", null)).isSameAs(pendiente);
        verify(confirmaciones).prepararEliminarCurso(2L);
    }

    private Curso curso(Long id, String nombre, String dia, int inicio, int fin) {
        HorarioAcademico horario = HorarioAcademico.builder().id(1L).usuario(usuario).build();
        return Curso.builder().id(id).horarioAcademico(horario).nombre(nombre).dia(dia)
                .horaInicio(LocalTime.of(inicio, 0)).horaFin(LocalTime.of(fin, 0)).build();
    }
}
