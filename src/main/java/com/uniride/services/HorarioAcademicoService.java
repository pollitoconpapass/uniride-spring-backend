package com.uniride.services;

import com.uniride.dto.responses.ArchivoRespuesta;
import com.uniride.dto.requests.CursoRequest;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HorarioAcademicoService {

    private static final long TAMANO_MAXIMO_BYTES = 1024L * 1024L;

    private final HorarioAcademicoRepository horarioAcademicoRepository;
    private final CursoRepository cursoRepository;
    private final ArchivoCargaRepository archivoCargaRepository;
    private final CursoMapper cursoMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public HorarioAcademicoService(HorarioAcademicoRepository horarioAcademicoRepository,
            CursoRepository cursoRepository, ArchivoCargaRepository archivoCargaRepository,
            CursoMapper cursoMapper, UsuarioService usuarioService,
            NotificacionService notificacionService) {
        this.horarioAcademicoRepository = horarioAcademicoRepository;
        this.cursoRepository = cursoRepository;
        this.archivoCargaRepository = archivoCargaRepository;
        this.cursoMapper = cursoMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional
    public List<CursoRespuesta> guardarCursos(List<CursoRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new CamposInvalidosException("Debes ingresar al menos un curso");
        }

        Usuario usuario = usuarioService.usuarioActual();
        HorarioAcademico horario = obtenerOCrearHorario(usuario);

        List<Curso> cursos = new ArrayList<>();
        for (CursoRequest request : requests) {
            validarCurso(request);
            Curso curso = cursoMapper.toCurso(request);
            curso.setHorarioAcademico(horario);
            cursos.add(curso);
        }

        List<Curso> guardados = cursoRepository.saveAll(cursos);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Cursos y horarios guardados",
                "Tus cursos y horarios fueron guardados correctamente.");

        return cursoMapper.toListaCursoRespuesta(guardados);
    }

    @Transactional(readOnly = true)
    public Page<CursoRespuesta> listarCursos(Pageable pageable) {
        Usuario usuario = usuarioService.usuarioActual();
        return cursoRepository.buscarPorUsuario(usuario.getId(), pageable)
                .map(cursoMapper::toCursoRespuesta);
    }

    @Transactional
    public void eliminarCurso(Long id) {
        Usuario usuario = usuarioService.usuarioActual();
        Curso curso = cursoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Curso no encontrado"));

        if (!curso.getHorarioAcademico().getUsuario().getId().equals(usuario.getId())) {
            throw new ResourceNotFoundException("Curso no encontrado");
        }

        cursoRepository.delete(curso);
    }

    @Transactional
    public ArchivoRespuesta subirArchivo(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new CamposInvalidosException("Debes adjuntar un archivo");
        }

        String nombreArchivo = archivo.getOriginalFilename() == null ? "" : archivo.getOriginalFilename();
        String formato = extraerFormato(nombreArchivo);

        if (!"txt".equals(formato) && !"pdf".equals(formato)) {
            throw new CamposInvalidosException("Solo se permiten archivos en formato .txt o .pdf");
        }

        if (archivo.getSize() > TAMANO_MAXIMO_BYTES) {
            throw new CamposInvalidosException("El archivo no puede superar un tamaño de 1 MB");
        }

        Usuario usuario = usuarioService.usuarioActual();
        HorarioAcademico horario = obtenerOCrearHorario(usuario);

        int cursosImportados = 0;
        if ("txt".equals(formato)) {
            cursosImportados = importarCursosDesdeTxt(archivo, horario);
        }

        archivoCargaRepository.save(ArchivoCarga.builder()
                .horarioAcademico(horario)
                .nombre(nombreArchivo)
                .formato(formato)
                .tamanoMb(archivo.getSize() / (1024.0 * 1024.0))
                .build());

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Archivo cargado",
                "El archivo " + nombreArchivo + " fue cargado correctamente.");

        String mensaje = cursosImportados > 0
                ? "Archivo subido correctamente. Se importaron " + cursosImportados + " cursos."
                : "Archivo subido correctamente.";

        return new ArchivoRespuesta(mensaje, cursosImportados, nombreArchivo);
    }

    private void validarCurso(CursoRequest request) {
        if (request.nombre() == null || request.nombre().isBlank()) {
            throw new CamposInvalidosException("Falta ingresar el nombre de un curso");
        }
        if (request.dia() == null || request.dia().isBlank()) {
            throw new CamposInvalidosException("Falta ingresar el día de un curso");
        }
        if (request.horaInicio() == null) {
            throw new CamposInvalidosException("Falta ingresar la hora de inicio de un curso");
        }
        if (request.horaFin() == null) {
            throw new CamposInvalidosException("Falta ingresar la hora de fin de un curso");
        }
        if (!request.horaFin().isAfter(request.horaInicio())) {
            throw new CamposInvalidosException("La hora de fin debe ser posterior a la hora de inicio");
        }
    }

    private HorarioAcademico obtenerOCrearHorario(Usuario usuario) {
        return horarioAcademicoRepository.findByUsuarioId(usuario.getId())
                .orElseGet(() -> horarioAcademicoRepository.save(
                        HorarioAcademico.builder().usuario(usuario).build()));
    }

    private String extraerFormato(String nombreArchivo) {
        String nombre = nombreArchivo.toLowerCase();
        if (nombre.endsWith(".txt")) {
            return "txt";
        }
        if (nombre.endsWith(".pdf")) {
            return "pdf";
        }
        return "";
    }

    private int importarCursosDesdeTxt(MultipartFile archivo, HorarioAcademico horario) {
        String contenido;
        try {
            contenido = new String(archivo.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo adjuntado");
        }

        List<Curso> cursos = new ArrayList<>();
        for (String linea : contenido.split("\\R")) {
            Curso curso = parsearLinea(linea, horario);
            if (curso != null) {
                cursos.add(curso);
            }
        }

        if (!cursos.isEmpty()) {
            cursoRepository.saveAll(cursos);
        }
        return cursos.size();
    }

    private Curso parsearLinea(String linea, HorarioAcademico horario) {
        if (linea == null || linea.isBlank()) {
            return null;
        }

        String[] partes = linea.trim().split(";");
        if (partes.length != 3) {
            return null;
        }

        String[] horas = partes[2].trim().split("-");
        if (horas.length != 2) {
            return null;
        }

        try {
            LocalTime horaInicio = LocalTime.parse(horas[0].trim());
            LocalTime horaFin = LocalTime.parse(horas[1].trim());
            if (!horaFin.isAfter(horaInicio)) {
                return null;
            }
            return Curso.builder()
                    .horarioAcademico(horario)
                    .nombre(partes[0].trim())
                    .dia(partes[1].trim())
                    .horaInicio(horaInicio)
                    .horaFin(horaFin)
                    .build();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
