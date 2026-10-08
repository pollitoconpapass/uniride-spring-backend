package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uniride.dto.requests.CursoRequest;
import com.uniride.dto.responses.ArchivoRespuesta;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.entities.Curso;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.mappers.CursoMapper;
import com.uniride.repositories.ArchivoCargaRepository;
import com.uniride.repositories.CursoRepository;
import com.uniride.repositories.HorarioAcademicoRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class HorarioAcademicoServiceTest {

    @Mock
    private HorarioAcademicoRepository horarioAcademicoRepository;

    @Mock
    private CursoRepository cursoRepository;

    @Mock
    private ArchivoCargaRepository archivoCargaRepository;

    @Mock
    private CursoMapper cursoMapper;

    @Mock
    private UsuarioService usuarioService;

    @Mock
    private NotificacionService notificacionService;

    private HorarioAcademicoService horarioAcademicoService;

    private Usuario usuario;
    private HorarioAcademico horario;

    @BeforeEach
    void setUp() {
        horarioAcademicoService = new HorarioAcademicoService(
                horarioAcademicoRepository,
                cursoRepository,
                archivoCargaRepository,
                cursoMapper,
                usuarioService,
                notificacionService,
                "spa+eng",
                ""
        );

        usuario = Usuario.builder()
                .id(1L)
                .nombre("Usuario")
                .build();

        horario = HorarioAcademico.builder()
                .id(10L)
                .usuario(usuario)
                .build();
    }

    @Test
    void us24DebeGuardarCursoIngresadoManualmente() {

        CursoRequest request = new CursoRequest(
                "Ingeniería de Software",
                "Lunes",
                LocalTime.of(8, 0),
                LocalTime.of(10, 0)
        );

        Curso curso = Curso.builder()
                .nombre("Ingeniería de Software")
                .dia("Lunes")
                .horaInicio(LocalTime.of(8, 0))
                .horaFin(LocalTime.of(10, 0))
                .build();

        CursoRespuesta respuestaEsperada = new CursoRespuesta(
                20L,
                "Ingeniería de Software",
                "Lunes",
                LocalTime.of(8, 0),
                LocalTime.of(10, 0)
        );

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L))
                .thenReturn(Optional.of(horario));

        when(cursoMapper.toCurso(request)).thenReturn(curso);

        when(cursoRepository.buscarConflictos(
                10L,
                "Lunes",
                LocalTime.of(8, 0),
                LocalTime.of(10, 0)))
                .thenReturn(List.of());

        when(cursoRepository.saveAll(anyList()))
                .thenReturn(List.of(curso));

        when(cursoMapper.toListaCursoRespuesta(anyList()))
                .thenReturn(List.of(respuestaEsperada));

        List<CursoRespuesta> resultado =
                horarioAcademicoService.guardarCursos(List.of(request));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.getFirst().nombre())
                .isEqualTo("Ingeniería de Software");

        verify(cursoRepository).saveAll(anyList());
    }

    @Test
    void us23DebeImportarCursosDesdePdf() {

        HorarioAcademicoService servicioSpy = spy(horarioAcademicoService);

        String contenidoHorario = """
                Lunes 5
                Ingeniería de Software - 1ACC0236
                08:00 - 10:00
                """;

        MockMultipartFile archivo = new MockMultipartFile(
                "archivo",
                "horario.pdf",
                "application/pdf",
                "contenido-simulado".getBytes()
        );

        when(usuarioService.usuarioActual()).thenReturn(usuario);

        when(horarioAcademicoRepository.findByUsuarioId(1L))
                .thenReturn(Optional.of(horario));

        doReturn(contenidoHorario)
                .when(servicioSpy)
                .extraerTexto(any(byte[].class));

        when(cursoRepository
                .existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(LocalTime.class)))
                .thenReturn(false);

        when(cursoRepository.buscarConflictos(
                anyLong(),
                anyString(),
                any(LocalTime.class),
                any(LocalTime.class)))
                .thenReturn(List.of());

        ArchivoRespuesta resultado =
                servicioSpy.subirArchivo(archivo);

        assertThat(resultado.cursosImportados()).isEqualTo(1);
        assertThat(resultado.cursosOmitidos()).isZero();
        assertThat(resultado.nombreArchivo()).isEqualTo("horario.pdf");

        verify(cursoRepository).saveAll(anyList());
    }

    @Test
    void us23DebeImportarCursosDesdeTxt() {

        String contenidoHorario = """
            Lunes 5
            Ingeniería de Software - 1ACC0236
            08:00 - 10:00
            """;

        MockMultipartFile archivo = new MockMultipartFile(
                "archivo",
                "horario.txt",
                "text/plain",
                contenidoHorario.getBytes(StandardCharsets.UTF_8)
        );

        when(usuarioService.usuarioActual())
                .thenReturn(usuario);

        when(horarioAcademicoRepository.findByUsuarioId(1L))
                .thenReturn(Optional.of(horario));

        when(cursoRepository
                .existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(LocalTime.class)))
                .thenReturn(false);

        when(cursoRepository.buscarConflictos(
                anyLong(),
                anyString(),
                any(LocalTime.class),
                any(LocalTime.class)))
                .thenReturn(List.of());

        ArchivoRespuesta resultado =
                horarioAcademicoService.subirArchivo(archivo);

        assertThat(resultado.cursosImportados())
                .isEqualTo(1);

        assertThat(resultado.cursosOmitidos())
                .isZero();

        assertThat(resultado.nombreArchivo())
                .isEqualTo("horario.txt");

        verify(cursoRepository).saveAll(anyList());
    }
}