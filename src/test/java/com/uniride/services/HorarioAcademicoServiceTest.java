package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uniride.dto.requests.CursoRequest;
import com.uniride.dto.responses.ArchivoRespuesta;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.entities.ArchivoCarga;
import com.uniride.entities.Curso;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.CursoMapper;
import com.uniride.repositories.ArchivoCargaRepository;
import com.uniride.repositories.CursoRepository;
import com.uniride.repositories.HorarioAcademicoRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
                .correoInstitucional("usuario@upc.edu.pe")
                .build();

        horario = HorarioAcademico.builder()
                .id(10L)
                .usuario(usuario)
                .build();
    }

    // ==========================================
    // PRUEBAS DE US24: REGISTRO Y GESTIÓN MANUAL
    // ==========================================

    @Test
    @DisplayName("US24: Debe guardar curso ingresado manualmente de manera exitosa")
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
        verify(notificacionService).notificar(eq(usuario), eq(TipoNotificacion.EXITO), anyString(), anyString());
    }

    @Test
    @DisplayName("US24: Debe lanzar excepción si la lista de cursos a guardar es nula o vacía")
    void us24DebeLanzarExcepcionCuandoListaDeCursosEsVaciaONula() {
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(null))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Debes ingresar al menos un curso");

        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of()))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Debes ingresar al menos un curso");
    }

    @Test
    @DisplayName("US24: Debe validar campos obligatorios individuales del curso")
    void us24DebeLanzarExcepcionCuandoFaltanCamposObligatorios() {
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));

        // Nombre nulo o en blanco
        CursoRequest sinNombre = new CursoRequest("", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0));
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(sinNombre)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Falta ingresar el nombre de un curso");

        // Día nulo o en blanco
        CursoRequest sinDia = new CursoRequest("Física I", "   ", LocalTime.of(8, 0), LocalTime.of(10, 0));
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(sinDia)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Falta ingresar el día de un curso");

        // Hora inicio nula
        CursoRequest sinHoraInicio = new CursoRequest("Física I", "Lunes", null, LocalTime.of(10, 0));
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(sinHoraInicio)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Falta ingresar la hora de inicio de un curso");

        // Hora fin nula
        CursoRequest sinHoraFin = new CursoRequest("Física I", "Lunes", LocalTime.of(8, 0), null);
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(sinHoraFin)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Falta ingresar la hora de fin de un curso");
    }

    @Test
    @DisplayName("US24: Debe validar que hora fin sea posterior a hora inicio")
    void us24DebeLanzarExcepcionCuandoHoraFinNoEsPosteriorAHoraInicio() {
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));

        CursoRequest horaInvertida = new CursoRequest("Química", "Martes", LocalTime.of(12, 0), LocalTime.of(10, 0));
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(horaInvertida)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("La hora de fin debe ser posterior a la hora de inicio");

        CursoRequest mismaHora = new CursoRequest("Química", "Martes", LocalTime.of(10, 0), LocalTime.of(10, 0));
        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(mismaHora)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("La hora de fin debe ser posterior a la hora de inicio");
    }

    @Test
    @DisplayName("US24: Debe lanzar BusinessException cuando hay conflicto con curso existente en BD")
    void us24DebeLanzarExcepcionCuandoHayConflictoConCursoExistenteEnBd() {
        CursoRequest request = new CursoRequest(
                "Algoritmos",
                "Lunes",
                LocalTime.of(9, 0),
                LocalTime.of(11, 0)
        );

        Curso cursoNuevo = Curso.builder()
                .nombre("Algoritmos")
                .dia("Lunes")
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(11, 0))
                .build();

        Curso cursoExistente = Curso.builder()
                .nombre("Cálculo I")
                .dia("Lunes")
                .horaInicio(LocalTime.of(8, 0))
                .horaFin(LocalTime.of(10, 0))
                .build();

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));
        when(cursoMapper.toCurso(request)).thenReturn(cursoNuevo);
        when(cursoRepository.buscarConflictos(10L, "Lunes", LocalTime.of(9, 0), LocalTime.of(11, 0)))
                .thenReturn(List.of(cursoExistente));

        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(request)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Conflicto de horario")
                .hasMessageContaining("Cálculo I")
                .hasMessageContaining("Algoritmos");

        verify(cursoRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("US24: Debe lanzar BusinessException si hay conflicto entre dos cursos de la misma solicitud")
    void us24DebeLanzarExcepcionCuandoHayConflictoEntreCursosDeLaMismaLista() {
        CursoRequest request1 = new CursoRequest(
                "Base de Datos",
                "Miércoles",
                LocalTime.of(8, 0),
                LocalTime.of(11, 0)
        );
        CursoRequest request2 = new CursoRequest(
                "Sistemas Operativos",
                "Miércoles",
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        Curso curso1 = Curso.builder()
                .nombre("Base de Datos")
                .dia("Miércoles")
                .horaInicio(LocalTime.of(8, 0))
                .horaFin(LocalTime.of(11, 0))
                .build();
        Curso curso2 = Curso.builder()
                .nombre("Sistemas Operativos")
                .dia("Miércoles")
                .horaInicio(LocalTime.of(10, 0))
                .horaFin(LocalTime.of(12, 0))
                .build();

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));
        when(cursoMapper.toCurso(request1)).thenReturn(curso1);
        when(cursoMapper.toCurso(request2)).thenReturn(curso2);
        when(cursoRepository.buscarConflictos(10L, "Miércoles", LocalTime.of(8, 0), LocalTime.of(11, 0)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> horarioAcademicoService.guardarCursos(List.of(request1, request2)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Conflicto de horario")
                .hasMessageContaining("Base de Datos")
                .hasMessageContaining("Sistemas Operativos");

        verify(cursoRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("US24: Debe listar cursos paginados del usuario autenticado")
    void us24DebeListarCursosDelUsuarioPaginados() {
        Pageable pageable = PageRequest.of(0, 10);
        Curso curso = Curso.builder()
                .id(1L)
                .nombre("Ingeniería de Software")
                .dia("Lunes")
                .horaInicio(LocalTime.of(8, 0))
                .horaFin(LocalTime.of(10, 0))
                .build();

        CursoRespuesta respuesta = new CursoRespuesta(
                1L, "Ingeniería de Software", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0));

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.buscarPorUsuario(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(curso)));
        when(cursoMapper.toCursoRespuesta(curso)).thenReturn(respuesta);

        Page<CursoRespuesta> resultado = horarioAcademicoService.listarCursos(pageable);

        assertThat(resultado.getContent()).hasSize(1);
        assertThat(resultado.getContent().getFirst().nombre()).isEqualTo("Ingeniería de Software");
        verify(cursoRepository).buscarPorUsuario(1L, pageable);
    }

    @Test
    @DisplayName("US24: Debe eliminar curso propio de manera exitosa")
    void us24DebeEliminarCursoPropioExitosamente() {
        Curso cursoPropio = Curso.builder()
                .id(5L)
                .nombre("Redes y Comunicaciones")
                .horarioAcademico(horario)
                .build();

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.findById(5L)).thenReturn(Optional.of(cursoPropio));

        horarioAcademicoService.eliminarCurso(5L);

        verify(cursoRepository).delete(cursoPropio);
    }

    @Test
    @DisplayName("US24: Debe lanzar ResourceNotFoundException si el curso a eliminar no existe")
    void us24DebeLanzarExcepcionAlEliminarCursoInexistente() {
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> horarioAcademicoService.eliminarCurso(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Curso no encontrado");

        verify(cursoRepository, never()).delete(any());
    }

    @Test
    @DisplayName("US24: Debe lanzar ResourceNotFoundException si el curso pertenece al horario de otro usuario")
    void us24DebeLanzarExcepcionAlEliminarCursoDeOtroUsuario() {
        Usuario otroUsuario = Usuario.builder().id(999L).build();
        HorarioAcademico otroHorario = HorarioAcademico.builder().id(20L).usuario(otroUsuario).build();
        Curso cursoAjeno = Curso.builder()
                .id(5L)
                .nombre("Redes y Comunicaciones")
                .horarioAcademico(otroHorario)
                .build();

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(cursoRepository.findById(5L)).thenReturn(Optional.of(cursoAjeno));

        assertThatThrownBy(() -> horarioAcademicoService.eliminarCurso(5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Curso no encontrado");

        verify(cursoRepository, never()).delete(any());
    }

    // ===========================================
    // PRUEBAS DE US23: IMPORTACIÓN DE PDF Y ARCHIVO
    // ===========================================

    @Test
    @DisplayName("US23: Debe importar cursos desde un archivo PDF válido")
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
        verify(archivoCargaRepository).save(any(ArchivoCarga.class));
        verify(notificacionService).notificar(eq(usuario), eq(TipoNotificacion.EXITO), eq("Archivo cargado"), anyString());
    }

    @Test
    @DisplayName("US23: Debe lanzar excepción si el archivo es nulo o está vacío")
    void us23DebeLanzarExcepcionCuandoArchivoEsNuloOVacio() {
        assertThatThrownBy(() -> horarioAcademicoService.subirArchivo(null))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Debes adjuntar un archivo");

        MockMultipartFile vacio = new MockMultipartFile("archivo", "horario.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> horarioAcademicoService.subirArchivo(vacio))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Debes adjuntar un archivo");
    }

    @Test
    @DisplayName("US23: Debe lanzar excepción si el formato del archivo no es PDF")
    void us23DebeLanzarExcepcionCuandoFormatoNoEsPdf() {
        MockMultipartFile docx = new MockMultipartFile(
                "archivo",
                "horario.docx",
                "application/octet-stream",
                "contenido".getBytes()
        );

        assertThatThrownBy(() -> horarioAcademicoService.subirArchivo(docx))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Solo se permiten archivos en formato .pdf");
    }

    @Test
    @DisplayName("US23: Debe lanzar excepción si el archivo supera el límite de 1 MB")
    void us23DebeLanzarExcepcionCuandoArchivoSuperaLimiteDe1MB() {
        byte[] bytesGrandes = new byte[(1024 * 1024) + 1];
        MockMultipartFile archivoGrande = new MockMultipartFile(
                "archivo",
                "horario.pdf",
                "application/pdf",
                bytesGrandes
        );

        assertThatThrownBy(() -> horarioAcademicoService.subirArchivo(archivoGrande))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("El archivo no puede superar un tamaño de 1 MB");
    }

    @Test
    @DisplayName("US23: Debe lanzar BusinessException si el PDF no contiene texto legible")
    void us23DebeLanzarExcepcionCuandoPdfNoContieneTextoLegible() {
        HorarioAcademicoService servicioSpy = spy(horarioAcademicoService);
        MockMultipartFile archivo = new MockMultipartFile(
                "archivo",
                "horario.pdf",
                "application/pdf",
                "simulado".getBytes()
        );

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));
        doReturn("   ").when(servicioSpy).extraerTexto(any(byte[].class));

        assertThatThrownBy(() -> servicioSpy.subirArchivo(archivo))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no contiene texto extraíble");
    }

    @Test
    @DisplayName("US23: Debe omitir cursos con conflicto de horario y notificar advertencia al usuario")
    void us23DebeOmitirCursosEnConflictoYNotificarAdvertencia() {
        HorarioAcademicoService servicioSpy = spy(horarioAcademicoService);

        String contenido = """
                Lunes 5
                Ingeniería de Software - 1ACC0236
                08:00 - 10:00
                Física I - 1ACC0237
                09:00 - 11:00
                """;

        MockMultipartFile archivo = new MockMultipartFile(
                "archivo",
                "horario.pdf",
                "application/pdf",
                "contenido".getBytes()
        );

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));
        doReturn(contenido).when(servicioSpy).extraerTexto(any(byte[].class));

        when(cursoRepository.existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                anyLong(), anyString(), anyString(), any(LocalTime.class)))
                .thenReturn(false);

        // Sin conflicto en BD para el primer curso
        when(cursoRepository.buscarConflictos(anyLong(), anyString(), any(LocalTime.class), any(LocalTime.class)))
                .thenReturn(List.of());

        ArchivoRespuesta respuesta = servicioSpy.subirArchivo(archivo);

        assertThat(respuesta.cursosImportados()).isEqualTo(1);
        assertThat(respuesta.cursosOmitidos()).isEqualTo(1);
        assertThat(respuesta.nombreArchivo()).isEqualTo("horario.pdf");
        assertThat(respuesta.mensaje()).contains("1 omitidos por conflicto de horario");

        verify(archivoCargaRepository).save(any(ArchivoCarga.class));
        verify(notificacionService).notificar(eq(usuario), eq(TipoNotificacion.ADVERTENCIA), eq("Archivo cargado"), anyString());
    }

    @Test
    @DisplayName("US23: Debe omitir cursos duplicados dentro del mismo PDF")
    void us23DebeOmitirCursosDuplicadosEnElMismoPdf() {
        HorarioAcademicoService servicioSpy = spy(horarioAcademicoService);

        String contenidoRepetido = """
                Lunes 5
                Ingeniería de Software - 1ACC0236
                08:00 - 10:00
                Ingeniería de Software - 1ACC0236
                08:00 - 10:00
                """;

        MockMultipartFile archivo = new MockMultipartFile(
                "archivo",
                "horario.pdf",
                "application/pdf",
                "contenido".getBytes()
        );

        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(horarioAcademicoRepository.findByUsuarioId(1L)).thenReturn(Optional.of(horario));
        doReturn(contenidoRepetido).when(servicioSpy).extraerTexto(any(byte[].class));

        when(cursoRepository.existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                anyLong(), anyString(), anyString(), any(LocalTime.class)))
                .thenReturn(false);
        when(cursoRepository.buscarConflictos(anyLong(), anyString(), any(LocalTime.class), any(LocalTime.class)))
                .thenReturn(List.of());

        ArchivoRespuesta respuesta = servicioSpy.subirArchivo(archivo);

        assertThat(respuesta.cursosImportados()).isEqualTo(1);
        assertThat(respuesta.cursosOmitidos()).isZero();
    }
}