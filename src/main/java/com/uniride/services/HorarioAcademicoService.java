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
import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HorarioAcademicoService {

    private static final long TAMANO_MAXIMO_BYTES = 1024L * 1024L;
    private static final int OCR_DPI = 300;
    private static final List<String> RUTAS_TESSDATA = List.of(
            "/opt/homebrew/share/tessdata",
            "/opt/homebrew/share/tesseract/5/tessdata",
            "/opt/homebrew/opt/tesseract/share/tessdata",
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata");
    private static final Pattern PATRON_DIA = Pattern.compile(
            "(?i)^\\s*(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\b");
    private static final Pattern PATRON_HORARIO =
            Pattern.compile("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern PATRON_CODIGO_CURSO = Pattern.compile(
            "\\s*-\\s*(?=[A-Za-z0-9?]*\\d)[A-Za-z0-9?]{4,}\\s*$");
    private static final Pattern PREFIJO_DIA = Pattern.compile(
            "(?i)^\\s*(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\s+\\d{1,2}\\b");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("H:mm");

    private final HorarioAcademicoRepository horarioAcademicoRepository;
    private final CursoRepository cursoRepository;
    private final ArchivoCargaRepository archivoCargaRepository;
    private final CursoMapper cursoMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;
    private final String idiomaOcr;
    private final String rutaTessdataConfigurada;

    public HorarioAcademicoService(HorarioAcademicoRepository horarioAcademicoRepository,
            CursoRepository cursoRepository, ArchivoCargaRepository archivoCargaRepository,
            CursoMapper cursoMapper, UsuarioService usuarioService,
            NotificacionService notificacionService,
            @Value("${uniride.ocr.idioma:spa+eng}") String idiomaOcr,
            @Value("${uniride.ocr.tessdata:}") String rutaTessdataConfigurada) {
        this.horarioAcademicoRepository = horarioAcademicoRepository;
        this.cursoRepository = cursoRepository;
        this.archivoCargaRepository = archivoCargaRepository;
        this.cursoMapper = cursoMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
        this.idiomaOcr = idiomaOcr;
        this.rutaTessdataConfigurada = rutaTessdataConfigurada;
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
        try {
            texto = extraerTexto(archivo.getBytes());
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo PDF adjuntado");
        }
        if (texto.isBlank()) {
            throw new BusinessException(
                    "No se pudo leer el PDF: no contiene texto extraíble ni texto reconocible por OCR");
        }

        List<Curso> cursos = new ArrayList<>();
        Set<String> clavesVistas = new HashSet<>();

        for (CursoParseado parseado : parsearCursos(texto)) {
            String clave = parseado.nombre() + "|" + parseado.dia() + "|" + parseado.horaInicio();
            if (!clavesVistas.add(clave)) {
                continue;
            }
            if (cursoRepository.existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                    horario.getId(), parseado.nombre(), parseado.dia(), parseado.horaInicio())) {
                continue;
            }

            cursos.add(Curso.builder()
                    .horarioAcademico(horario)
                    .nombre(parseado.nombre())
                    .dia(parseado.dia())
                    .horaInicio(parseado.horaInicio())
                    .horaFin(parseado.horaFin())
                    .build());
        }

        if (!cursos.isEmpty()) {
            cursoRepository.saveAll(cursos);
        }
        return cursos.size();
    }

    String extraerTexto(byte[] bytes) {
        try (PDDocument documento = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String texto = stripper.getText(documento);
            if (texto != null && !texto.isBlank()) {
                return texto;
            }
            return ocrDocumento(documento);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo PDF adjuntado");
        } catch (TesseractException e) {
            throw new BusinessException("No se pudo reconocer el PDF con OCR: " + e.getMessage());
        } catch (UnsatisfiedLinkError e) {
            throw new BusinessException(
                    "El motor de OCR (Tesseract) no está disponible en esta máquina");
        }
    }

    private String ocrDocumento(PDDocument documento) throws IOException, TesseractException {
        Tesseract tesseract = crearTesseract();
        PDFRenderer renderer = new PDFRenderer(documento);
        StringBuilder texto = new StringBuilder();
        for (int pagina = 0; pagina < documento.getNumberOfPages(); pagina++) {
            BufferedImage imagen = renderer.renderImageWithDPI(pagina, OCR_DPI);
            texto.append(tesseract.doOCR(imagen)).append('\n');
        }
        return texto.toString();
    }

    private Tesseract crearTesseract() {
        String carpetaLibrerias = "/opt/homebrew/lib";
        if (System.getProperty("jna.library.path") == null
                && new File(carpetaLibrerias, "libtesseract.dylib").exists()) {
            System.setProperty("jna.library.path", carpetaLibrerias);
        }

        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(resolverRutaTessdata());
        tesseract.setLanguage(idiomaOcr == null || idiomaOcr.isBlank() ? "spa+eng" : idiomaOcr);
        return tesseract;
    }

    private String resolverRutaTessdata() {
        if (rutaTessdataConfigurada != null && !rutaTessdataConfigurada.isBlank()) {
            if (!contieneDatosTesseract(rutaTessdataConfigurada)) {
                throw new BusinessException("La propiedad uniride.ocr.tessdata no contiene datos de "
                        + "Tesseract: " + rutaTessdataConfigurada);
            }
            return rutaTessdataConfigurada;
        }

        List<String> candidatas = new ArrayList<>();
        String variableEntorno = System.getenv("TESSDATA_PREFIX");
        if (variableEntorno != null && !variableEntorno.isBlank()) {
            candidatas.add(variableEntorno);
        }
        candidatas.addAll(RUTAS_TESSDATA);

        for (String candidata : candidatas) {
            if (contieneDatosTesseract(candidata)) {
                return candidata;
            }
        }
        throw new BusinessException("No se encontró el directorio de datos de Tesseract (tessdata); "
                + "instala tesseract-lang o configura la propiedad uniride.ocr.tessdata");
    }

    private boolean contieneDatosTesseract(String ruta) {
        File[] datos = new File(ruta)
                .listFiles((dir, nombre) -> nombre.endsWith(".traineddata"));
        return datos != null && datos.length > 0;
    }

    record CursoParseado(String nombre, String dia, LocalTime horaInicio, LocalTime horaFin) {
    }

    List<CursoParseado> parsearCursos(String texto) {
        List<CursoParseado> cursos = new ArrayList<>();
        String diaActual = null;
        String nombrePendiente = null;

        for (String lineaCruda : texto.split("\\R")) {
            String linea = normalizarLinea(lineaCruda);
            if (linea.isEmpty()) {
                continue;
            }

            Matcher dia = PATRON_DIA.matcher(linea);
            if (dia.find()) {
                diaActual = capitalizar(dia.group(1));
                nombrePendiente = null;
            }

            Matcher horario = PATRON_HORARIO.matcher(linea);
            if (horario.find()) {
                if (diaActual == null) {
                    continue;
                }
                String nombre = limpiarNombre(linea.substring(0, horario.start()));
                if (nombre.isEmpty()) {
                    nombre = nombrePendiente;
                }
                if (nombre.isEmpty()) {
                    continue;
                }

                try {
                    LocalTime horaInicio = LocalTime.parse(horario.group(1), FORMATO_HORA);
                    LocalTime horaFin = LocalTime.parse(horario.group(2), FORMATO_HORA);
                    if (!horaFin.isAfter(horaInicio)) {
                        continue;
                    }
                    cursos.add(new CursoParseado(nombre, diaActual, horaInicio, horaFin));
                    nombrePendiente = null;
                } catch (DateTimeParseException e) {
                    continue;
                }
                continue;
            }

            if (PATRON_CODIGO_CURSO.matcher(linea).find()) {
                nombrePendiente = limpiarNombre(linea);
            }
        }
        return cursos;
    }

    private String normalizarLinea(String linea) {
        return linea.replace('\u00A0', ' ')
                .replaceAll("[\u2010-\u2015\u2212]", "-")
                .trim();
    }

    private String limpiarNombre(String nombre) {
        String limpio = nombre.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        limpio = PREFIJO_DIA.matcher(limpio).replaceFirst("").trim();
        limpio = PATRON_CODIGO_CURSO.matcher(limpio).replaceFirst("").trim();
        return limpio.replaceAll("[^\\p{L}\\p{N}]+$", "").trim();
    }

    private String capitalizar(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1).toLowerCase();
    }
}
