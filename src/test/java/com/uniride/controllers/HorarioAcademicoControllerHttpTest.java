package com.uniride.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.mappers.CursoMapper;
import com.uniride.repositories.ArchivoCargaRepository;
import com.uniride.repositories.CursoRepository;
import com.uniride.repositories.HorarioAcademicoRepository;
import com.uniride.services.HorarioAcademicoService;
import com.uniride.services.MensajeService;
import com.uniride.services.NotificacionService;
import com.uniride.services.UsuarioService;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Comprueba respuestas HTTP de US24, además de las pruebas existentes del servicio. */
class HorarioAcademicoControllerHttpTest {

    private final HorarioAcademicoRepository horarioRepository = mock(HorarioAcademicoRepository.class);
    private final CursoRepository cursoRepository = mock(CursoRepository.class);
    private final ArchivoCargaRepository archivoRepository = mock(ArchivoCargaRepository.class);
    private final CursoMapper cursoMapper = mock(CursoMapper.class);
    private final UsuarioService usuarioService = mock(UsuarioService.class);
    private final NotificacionService notificacionService = mock(NotificacionService.class);

    private MockMvc mvc;
    private Usuario usuario;

    @BeforeEach
    void preparar() {
        usuario = Usuario.builder().id(10L).build();
        HorarioAcademico horario = HorarioAcademico.builder().id(20L).usuario(usuario).build();
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioRepository.findByUsuarioId(10L)).thenReturn(Optional.of(horario));

        HorarioAcademicoService service = new HorarioAcademicoService(
                horarioRepository, cursoRepository, archivoRepository, cursoMapper,
                usuarioService, notificacionService, "spa+eng", "");
        MensajeService mensajes = new MensajeService(new StaticMessageSource());
        mvc = MockMvcBuilders.standaloneSetup(new HorarioAcademicoController(service))
                .setControllerAdvice(new GlobalExceptionHandler(mensajes))
                .build();
    }

    private void postearInvalido(String body, String mensaje) throws Exception {
        mvc.perform(post("/perfil/cursos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(mensaje));
        verify(cursoRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("US24 exitoso: curso válido devuelve 201 y se notifica al usuario")
    void cursoValidoDevuelve201() throws Exception {
        CursoRespuesta esperado = new CursoRespuesta(
                50L, "Álgebra", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0));
        when(cursoMapper.toCurso(any())).thenAnswer(inv -> {
            com.uniride.dto.requests.CursoRequest req = inv.getArgument(0);
            return com.uniride.entities.Curso.builder()
                    .nombre(req.nombre()).dia(req.dia())
                    .horaInicio(req.horaInicio()).horaFin(req.horaFin()).build();
        });
        when(cursoRepository.buscarConflictos(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());
        when(cursoRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(cursoMapper.toListaCursoRespuesta(anyList())).thenReturn(List.of(esperado));

        mvc.perform(post("/perfil/cursos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"nombre":"Álgebra","dia":"Lunes","horaInicio":"08:00","horaFin":"10:00"}]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].nombre").value("Álgebra"));

        verify(notificacionService).notificar(eq(usuario),
                eq(com.uniride.enums.TipoNotificacion.EXITO),
                eq("Cursos y horarios guardados"), anyString());
    }

    @Test
    @DisplayName("US24 error: conflicto de cursos devuelve 409 y no persiste el lote")
    void conflictoDevuelve409() throws Exception {
        when(cursoMapper.toCurso(any())).thenAnswer(inv -> {
            com.uniride.dto.requests.CursoRequest req = inv.getArgument(0);
            return com.uniride.entities.Curso.builder()
                    .nombre(req.nombre()).dia(req.dia())
                    .horaInicio(req.horaInicio()).horaFin(req.horaFin()).build();
        });
        com.uniride.entities.Curso existente = com.uniride.entities.Curso.builder()
                .nombre("Física").dia("Lunes")
                .horaInicio(LocalTime.of(9, 0)).horaFin(LocalTime.of(11, 0)).build();
        when(cursoRepository.buscarConflictos(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(existente));

        mvc.perform(post("/perfil/cursos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"nombre":"Álgebra","dia":"Lunes","horaInicio":"08:00","horaFin":"10:00"}]
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("Conflicto de horario")));

        verify(cursoRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("US24 error: nombre vacío devuelve 400 y mensaje específico")
    void nombreVacioEs400() throws Exception {
        postearInvalido("""
                [{"nombre":"","dia":"Lunes","horaInicio":"08:00","horaFin":"10:00"}]
                """, "Falta ingresar el nombre de un curso");
    }

    @Test
    @DisplayName("US24 error: día vacío devuelve 400 y mensaje específico")
    void diaVacioEs400() throws Exception {
        postearInvalido("""
                [{"nombre":"Álgebra","dia":" ","horaInicio":"08:00","horaFin":"10:00"}]
                """, "Falta ingresar el día de un curso");
    }

    @Test
    @DisplayName("US24 error: falta horaInicio, devuelve 400")
    void horaInicioFaltanteEs400() throws Exception {
        postearInvalido("""
                [{"nombre":"Álgebra","dia":"Lunes","horaFin":"10:00"}]
                """, "Falta ingresar la hora de inicio de un curso");
    }

    @Test
    @DisplayName("US24 error: falta horaFin, devuelve 400")
    void horaFinFaltanteEs400() throws Exception {
        postearInvalido("""
                [{"nombre":"Álgebra","dia":"Lunes","horaInicio":"08:00"}]
                """, "Falta ingresar la hora de fin de un curso");
    }

    @Test
    @DisplayName("US24 error: lista con elemento null devuelve 400, no 500")
    void elementoNuloEs400() throws Exception {
        postearInvalido("[null]", "Debes ingresar los datos del curso; no se permiten elementos nulos");
    }

    @Test
    @DisplayName("US24 error: lista vacía devuelve 400")
    void listaVaciaEs400() throws Exception {
        postearInvalido("[]", "Debes ingresar al menos un curso");
    }

    @Test
    @DisplayName("US24 error: hora inválida devuelve 400 y no 500")
    void horaMalFormadaEs400() throws Exception {
        mvc.perform(post("/perfil/cursos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"nombre":"Álgebra","dia":"Lunes","horaInicio":"25:99","horaFin":"10:00"}]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("formato")));
        verify(cursoRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("US24 error: JSON mal formado devuelve 400")
    void jsonMalFormadoEs400() throws Exception {
        mvc.perform(post("/perfil/cursos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{"))
                .andExpect(status().isBadRequest());
        verify(cursoRepository, never()).saveAll(anyList());
    }
}
