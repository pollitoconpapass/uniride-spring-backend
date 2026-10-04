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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HorarioAcademicoService {

    private static final long TAMANO_MAXIMO_BYTES = 1024L * 1024L;
    private static final Pattern PATRON_DIA = Pattern.compile(
            "(?i)^\\s*(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\b");
    private static final Pattern PATRON_HORARIO =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern PATRON_CODIGO_CURSO =
            Pattern.compile("\\s+-\\s+\\d{1,3}[A-Z]{1,4}\\d{3,6}\\s*$");
    private static final Pattern PREFIJO_DIA = Pattern.compile(
            "(?i)^\\s*(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\s+\\d{1,2}\\b");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("H:mm");

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

        if (!"pdf".equals(formato)) {
            throw new CamposInvalidosException("Solo se permiten archivos en formato .pdf");
        }

        if (archivo.getSize() > TAMANO_MAXIMO_BYTES) {
            throw new CamposInvalidosException("El archivo no puede superar un tamaño de 1 MB");
        }

        Usuario usuario = usuarioService.usuarioActual();
        HorarioAcademico horario = obtenerOCrearHorario(usuario);

        int cursosImportados = importarCursosDesdePdf(archivo, horario);

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
                : "Archivo subido correctamente. No se detectaron cursos en el PDF.";

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

    private int importarCursosDesdePdf(MultipartFile archivo, HorarioAcademico horario) {
        String texto;
        try (PDDocument documento = Loader.loadPDF(archivo.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            texto = stripper.getText(documento);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo PDF adjuntado");
        }

        texto = texto.replace('\u00A0', ' ');
        List<Curso> cursos = new ArrayList<>();
        Set<String> clavesVistas = new HashSet<>();
        String diaActual = null;

        for (String linea : texto.split("\\R")) {
            Matcher dia = PATRON_DIA.matcher(linea);
            if (dia.find()) {
                diaActual = capitalizar(dia.group(1));
            }

            Matcher horarioMatch = PATRON_HORARIO.matcher(linea);
            if (!horarioMatch.find() || diaActual == null) {
                continue;
            }

            String nombre = linea.substring(0, horarioMatch.start());
            nombre = PREFIJO_DIA.matcher(nombre).replaceFirst("").trim();
            nombre = PATRON_CODIGO_CURSO.matcher(nombre).replaceFirst("").trim();
            if (nombre.isBlank()) {
                continue;
            }

            try {
                LocalTime horaInicio = LocalTime.parse(horarioMatch.group(1), FORMATO_HORA);
                LocalTime horaFin = LocalTime.parse(horarioMatch.group(2), FORMATO_HORA);
                if (!horaFin.isAfter(horaInicio)) {
                    continue;
                }

                String clave = nombre + "|" + diaActual + "|" + horaInicio;
                if (!clavesVistas.add(clave)) {
                    continue;
                }
                if (cursoRepository.existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                        horario.getId(), nombre, diaActual, horaInicio)) {
                    continue;
                }

                cursos.add(Curso.builder()
                        .horarioAcademico(horario)
                        .nombre(nombre)
                        .dia(diaActual)
                        .horaInicio(horaInicio)
                        .horaFin(horaFin)
                        .build());
            } catch (DateTimeParseException e) {
                continue;
            }
        }

        if (!cursos.isEmpty()) {
            cursoRepository.saveAll(cursos);
        }
        return cursos.size();
    }

    private String capitalizar(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1).toLowerCase();
    }
}
