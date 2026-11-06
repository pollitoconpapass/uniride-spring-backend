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
import java.nio.charset.StandardCharsets;
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
    private static final int OCR_DPI = 200;
    private static final int MAX_PAGINAS = 10;
    private static final long MAX_PIXELES_PAGINA = 8_000_000;
    private static final int MAX_TEXTO = 1_000_000;
    private final java.util.concurrent.Semaphore procesadoresPdf = new java.util.concurrent.Semaphore(2);
    @Value("${uniride.ocr.timeout-segundos:60}") private int timeoutSegundos = 60;
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
            "-\\s*(?=[A-Za-z0-9?]*\\d)[A-Za-z0-9?]{4,}\\s*$");
    private static final Pattern PREFIJO_DIA = Pattern.compile(
            "(?i)^\\s*(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\s+\\d{1,2}\\b");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("H:mm");

    private final HorarioAcademicoRepository horarioAcademicoRepository;
    private final CursoRepository cursoRepository;
    private final ArchivoCargaRepository archivoCargaRepository;
    private final CursoMapper cursoMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<HorarioAcademicoService> self;
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
        if (requests.size() > 500) throw new CamposInvalidosException("No se permiten más de 500 cursos por importación");

        Usuario usuario = usuarioService.usuarioActual();
        HorarioAcademico horario = obtenerOCrearHorario(usuario);

        List<Curso> cursos = new ArrayList<>();
        for (CursoRequest request : requests) {
            validarCurso(request);
            Curso curso = cursoMapper.toCurso(request);
            curso.setHorarioAcademico(horario);

            Curso enConflicto = buscarConflicto(horario, curso, cursos);
            if (enConflicto != null) {
                throw new BusinessException(mensajeConflicto(enConflicto, curso));
            }
            cursos.add(curso);
        }

        List<Curso> guardados = cursoRepository.saveAll(cursos);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Cursos y horarios guardados",
                "Tus cursos y horarios fueron guardados correctamente.");

        return cursoMapper.toListaCursoRespuesta(guardados);
    }

    @Transactional(readOnly = true)
    public Page<CursoRespuesta> listarCursos(Pageable pageable) {
        com.uniride.util.Paginacion.validar(pageable.getPageNumber(), pageable.getPageSize());
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

    public ArchivoRespuesta subirArchivo(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new CamposInvalidosException("Debes adjuntar un archivo");
        }

        String nombreArchivo = archivo.getOriginalFilename() == null ? "" : archivo.getOriginalFilename();
        String formato = extraerFormato(nombreArchivo);

        if (!List.of("pdf", "txt").contains(formato)) {
            throw new CamposInvalidosException(
                    "Solo se permiten archivos en formato .pdf o .txt");
        }

        if (archivo.getSize() > TAMANO_MAXIMO_BYTES) {
            throw new CamposInvalidosException("El archivo no puede superar un tamaño de 1 MB");
        }

        String texto = "pdf".equals(formato) ? leerPdf(archivo) : leerTxt(archivo);
        // La extracción aislada no retiene una conexión ni bloqueos de base de datos.
        return (self == null ? this : self.getObject()).guardarImportacion(archivo, formato, texto);
    }

    @Transactional
    public ArchivoRespuesta guardarImportacion(MultipartFile archivo, String formato, String texto) {
        Usuario usuario = usuarioService.usuarioActual();
        HorarioAcademico horario = obtenerOCrearHorario(usuario);
        String nombreArchivo = archivo.getOriginalFilename();
        ImportacionArchivo importacion = importarCursosDesdeTexto(texto, horario);

        archivoCargaRepository.save(ArchivoCarga.builder()
                .horarioAcademico(horario)
                .nombre(nombreArchivo)
                .formato(formato)
                .tamanoMb(archivo.getSize() / (1024.0 * 1024.0))
                .build());

        boolean hayConflictos = !importacion.conflictos().isEmpty();
        notificacionService.notificar(usuario,
                hayConflictos ? TipoNotificacion.ADVERTENCIA : TipoNotificacion.EXITO,
                "Archivo cargado",
                "El archivo " + nombreArchivo + " fue cargado correctamente."
                        + (hayConflictos
                                ? " " + importacion.conflictos().size()
                                        + " cursos no se importaron por conflicto de horario."
                                : ""));

        return new ArchivoRespuesta(mensajeImportacion(importacion),
                importacion.importados(),
                importacion.conflictos().size(),
                nombreArchivo);
    }

    private String mensajeImportacion(ImportacionArchivo importacion) {
        List<String> conflictos = importacion.conflictos();
        String mensaje;
        if (importacion.importados() > 0) {
            mensaje = "Archivo subido correctamente. Se importaron "
                    + importacion.importados() + " cursos.";
        } else if (conflictos.isEmpty()) {
            mensaje = "Archivo subido correctamente. No se detectaron cursos en el archivo.";
        } else {
            mensaje = "Archivo subido correctamente. Ningún curso del archivo pudo importarse.";
        }
        if (!conflictos.isEmpty()) {
            mensaje += " " + conflictos.size() + " omitidos por conflicto de horario: "
                    + String.join(" | ", conflictos)
                    + ". Elimina o edita el curso existente y vuelve a subir el archivo.";
        }
        return mensaje;
    }

    private void validarCurso(CursoRequest request) {
        if (request == null) {
            throw new CamposInvalidosException("Debes ingresar los datos del curso; no se permiten elementos nulos");
        }
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

    private String leerPdf(MultipartFile archivo) {

        String texto;

        try {
            texto = extraerTexto(archivo.getBytes());
        } catch (IOException e) {
            throw new BusinessException(
                    "No se pudo leer el archivo PDF adjuntado");
        }

        if (texto.isBlank()) {
            throw new BusinessException(
                    "No se pudo leer el PDF: no contiene texto extraíble "
                            + "ni texto reconocible por OCR");
        }

        return texto;
    }

    private String leerTxt(MultipartFile archivo) {

        String texto;

        try {
            texto = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(archivo.getBytes())).toString();
        } catch (IOException e) {
            throw new BusinessException(
                    "No se pudo leer el archivo TXT adjuntado");
        }

        if (texto.isBlank()) {
            throw new BusinessException(
                    "El archivo TXT está vacío");
        }

        if (texto.startsWith("%PDF-") || texto.length() > MAX_TEXTO
                || texto.chars().anyMatch(c -> c == 0 || (c < 32 && c != 9 && c != 10 && c != 13))) {
            throw new CamposInvalidosException("El TXT debe contener texto UTF-8 válido");
        }

        return texto;
    }

    private ImportacionArchivo importarCursosDesdeTexto(
            String texto,
            HorarioAcademico horario) {

        List<Curso> cursos = new ArrayList<>();
        List<String> conflictos = new ArrayList<>();
        Set<String> clavesVistas = new HashSet<>();

        for (CursoParseado parseado : parsearCursos(texto)) {

            String clave = parseado.nombre()
                    + "|"
                    + parseado.dia()
                    + "|"
                    + parseado.horaInicio();

            if (!clavesVistas.add(clave)) {
                continue;
            }

            if (cursoRepository
                    .existsByHorarioAcademicoIdAndNombreAndDiaAndHoraInicio(
                            horario.getId(),
                            parseado.nombre(),
                            parseado.dia(),
                            parseado.horaInicio())) {
                continue;
            }

            Curso curso = Curso.builder()
                    .horarioAcademico(horario)
                    .nombre(parseado.nombre())
                    .dia(parseado.dia())
                    .horaInicio(parseado.horaInicio())
                    .horaFin(parseado.horaFin())
                    .build();

            Curso enConflicto =
                    buscarConflicto(horario, curso, cursos);

            if (enConflicto != null) {
                conflictos.add(describirCurso(curso));
                continue;
            }

            cursos.add(curso);
        }

        if (!cursos.isEmpty()) {
            cursoRepository.saveAll(cursos);
        }

        return new ImportacionArchivo(
                cursos.size(),
                conflictos);
    }

    private Curso buscarConflicto(HorarioAcademico horario, Curso curso, List<Curso> pendientes) {
        for (Curso previo : pendientes) {
            if (seSolapan(previo, curso)) {
                return previo;
            }
        }
        return cursoRepository.buscarConflictos(horario.getId(), curso.getDia(),
                        curso.getHoraInicio(), curso.getHoraFin())
                .stream().findFirst().orElse(null);
    }

    private boolean seSolapan(Curso primero, Curso segundo) {
        return primero.getDia().equalsIgnoreCase(segundo.getDia())
                && primero.getHoraInicio().isBefore(segundo.getHoraFin())
                && primero.getHoraFin().isAfter(segundo.getHoraInicio());
    }

    private String mensajeConflicto(Curso existente, Curso nuevo) {
        return "Conflicto de horario: " + describirCurso(existente)
                + " se solapa con " + describirCurso(nuevo)
                + ". Elimina o edita uno de los cursos antes de continuar.";
    }

    private String describirCurso(Curso curso) {
        return "«" + curso.getNombre() + "» (" + curso.getDia() + " de "
                + curso.getHoraInicio() + " a " + curso.getHoraFin() + ")";
    }

    record ImportacionArchivo(
            int importados,
            List<String> conflictos) {
    }

    String extraerTexto(byte[] bytes) {
        validarBytesPdf(bytes);
        if (!procesadoresPdf.tryAcquire()) throw new com.uniride.exceptions.DemasiadasSolicitudesException();
        java.nio.file.Path carpeta = null;
        Process proceso = null;
        try {
            carpeta = java.nio.file.Files.createTempDirectory("uniride-pdf-");
            var entrada = carpeta.resolve("entrada.pdf");
            var salida = carpeta.resolve("salida.txt");
            java.nio.file.Files.write(entrada, bytes);
            List<String> comando = new ArrayList<>();
            comando.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
            comando.add("-Xmx256m"); comando.add("-Djava.awt.headless=true");
            var origen = new org.springframework.boot.system.ApplicationHome(HorarioAcademicoService.class).getSource();
            if (origen != null && origen.isFile() && origen.getName().endsWith(".jar")) {
                comando.add("-Dloader.main=com.uniride.ocr.PdfWorker");
                comando.add("-cp"); comando.add(origen.getAbsolutePath());
                comando.add("org.springframework.boot.loader.launch.PropertiesLauncher");
            } else {
                comando.add("-cp"); comando.add(System.getProperty("java.class.path"));
                comando.add("com.uniride.ocr.PdfWorker");
            }
            comando.add(entrada.toString()); comando.add(salida.toString());
            comando.add(idiomaOcr == null ? "spa+eng" : idiomaOcr);
            comando.add(rutaTessdataConfigurada == null ? "" : rutaTessdataConfigurada);
            proceso = new ProcessBuilder(comando).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!proceso.waitFor(timeoutSegundos, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new CamposInvalidosException("El PDF superó el tiempo máximo de procesamiento");
            }
            if (proceso.exitValue() == 3) throw new CamposInvalidosException("El PDF supera los límites de procesamiento");
            if (proceso.exitValue() != 0 || !java.nio.file.Files.exists(salida)) {
                throw new BusinessException("No se pudo leer el PDF o supera los límites de procesamiento");
            }
            if (java.nio.file.Files.size(salida) > MAX_TEXTO * 4L) {
                throw new CamposInvalidosException("El PDF contiene demasiado texto");
            }
            return java.nio.file.Files.readString(salida, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new BusinessException("Procesamiento PDF interrumpido");
        } catch (IOException e) {
            throw new BusinessException("No se pudo procesar el PDF");
        } finally {
            if (proceso != null && proceso.isAlive()) {
                proceso.descendants().forEach(ProcessHandle::destroyForcibly);
                proceso.destroyForcibly();
                try { proceso.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (carpeta != null) {
                try {
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("entrada.pdf"));
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("salida.txt"));
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("pagina.png"));
                    java.nio.file.Files.deleteIfExists(carpeta.resolve("pagina.txt"));
                    java.nio.file.Files.deleteIfExists(carpeta);
                } catch (IOException e) { /* El SO podrá limpiar temporales tras liberar el proceso. */ }
            }
            procesadoresPdf.release();
        }
    }

    private void validarBytesPdf(byte[] bytes) {
        if (bytes == null || bytes.length > TAMANO_MAXIMO_BYTES || bytes.length < 5
                || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new CamposInvalidosException("El contenido no es un PDF válido de hasta 1 MB");
        }
    }

    public String extraerTextoWorker(byte[] bytes) {
        validarBytesPdf(bytes);
        if (!procesadoresPdf.tryAcquire()) {
            throw new com.uniride.exceptions.DemasiadasSolicitudesException();
        }
        try (PDDocument documento = Loader.loadPDF(bytes)) {
            if (documento.isEncrypted() || documento.getNumberOfPages() < 1
                    || documento.getNumberOfPages() > MAX_PAGINAS) {
                throw new CamposInvalidosException("El PDF debe tener entre 1 y 10 páginas y no estar cifrado");
            }
            long pixeles = 0;
            for (var pagina : documento.getPages()) {
                double ancho = pagina.getMediaBox().getWidth() * pagina.getUserUnit() * OCR_DPI / 72.0;
                double alto = pagina.getMediaBox().getHeight() * pagina.getUserUnit() * OCR_DPI / 72.0;
                double area = Math.ceil(ancho) * Math.ceil(alto);
                if (!Double.isFinite(area) || ancho <= 0 || alto <= 0 || area > MAX_PIXELES_PAGINA) {
                    throw new CamposInvalidosException("La resolución de la página PDF supera el límite permitido");
                }
                pixeles += (long) area;
                if (pixeles > 40_000_000) throw new CamposInvalidosException("El PDF supera el límite total de píxeles");
                // Acotar streams descomprimidos antes de pasarlos al intérprete de contenido.
                var contenidos = pagina.getContentStreams();
                while (contenidos.hasNext()) {
                    try (var stream = contenidos.next().createInputStream()) {
                        if (stream.readNBytes(2_000_001).length > 2_000_000) {
                            throw new CamposInvalidosException("El contenido expandido del PDF supera el límite");
                        }
                    }
                }
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            java.io.StringWriter salida = new java.io.StringWriter() {
                @Override public void write(String str, int off, int len) {
                    if (getBuffer().length() + len > MAX_TEXTO) throw new CamposInvalidosException("El PDF contiene demasiado texto");
                    super.write(str, off, len);
                }
                @Override public void write(char[] buf, int off, int len) {
                    if (getBuffer().length() + len > MAX_TEXTO) throw new CamposInvalidosException("El PDF contiene demasiado texto");
                    super.write(buf, off, len);
                }
            };
            stripper.writeText(documento, salida);
            String texto = salida.toString();
            if (texto != null && !texto.isBlank()) {
                return texto;
            }
            return ocrDocumento(documento);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo PDF adjuntado");
        } catch (TesseractException e) {
            throw new BusinessException("No se pudo reconocer el PDF con OCR");
        } catch (UnsatisfiedLinkError e) {
            throw new BusinessException(
                    "El motor de OCR (Tesseract) no está disponible en esta máquina");
        } finally {
            procesadoresPdf.release();
        }
    }

    private String ocrDocumento(PDDocument documento) throws IOException, TesseractException {
        boolean usaCli = java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/tesseract"));
        Tesseract tesseract = usaCli ? null : crearTesseract();
        PDFRenderer renderer = new PDFRenderer(documento);
        StringBuilder texto = new StringBuilder();
        long limite = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        for (int pagina = 0; pagina < documento.getNumberOfPages(); pagina++) {
            if (System.nanoTime() > limite) throw new CamposInvalidosException("El OCR superó el tiempo permitido");
            BufferedImage imagen = renderer.renderImageWithDPI(pagina, OCR_DPI);
            try { texto.append(usaCli ? ocrConCli(imagen) : tesseract.doOCR(imagen)).append('\n'); }
            finally { imagen.flush(); }
            if (texto.length() > MAX_TEXTO) throw new CamposInvalidosException("El PDF contiene demasiado texto");
        }
        return texto.toString();
    }

    private String ocrConCli(BufferedImage imagen) throws IOException {
        // Solo el worker establece este directorio generado por el padre, nunca el usuario.
        var carpeta = java.nio.file.Path.of(System.getProperty("uniride.ocr.tempdir"));
        var entrada = carpeta.resolve("pagina.png");
        var salida = carpeta.resolve("pagina.txt");
        Process proceso = null;
        try {
            javax.imageio.ImageIO.write(imagen, "png", entrada.toFile());
            proceso = new ProcessBuilder("/usr/bin/tesseract", entrada.toString(), "stdout",
                    "--tessdata-dir", resolverRutaTessdata(), "-l",
                    idiomaOcr == null || idiomaOcr.isBlank() ? "spa+eng" : idiomaOcr)
                    .redirectOutput(salida.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!proceso.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || proceso.exitValue() != 0) {
                throw new BusinessException("No se pudo reconocer el PDF con OCR");
            }
            if (java.nio.file.Files.size(salida) > MAX_TEXTO * 4L) {
                throw new CamposInvalidosException("El PDF contiene demasiado texto");
            }
            return java.nio.file.Files.readString(salida, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new BusinessException("OCR interrumpido");
        } finally {
            if (proceso != null && proceso.isAlive()) proceso.destroyForcibly();
            java.nio.file.Files.deleteIfExists(entrada);
            java.nio.file.Files.deleteIfExists(salida);
        }
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

        if (texto.length() > MAX_TEXTO) throw new CamposInvalidosException("El texto supera el límite permitido");
        List<String> lineas = Pattern.compile("\\R").splitAsStream(texto).limit(2001).toList();
        if (lineas.size() > 2000) throw new CamposInvalidosException("El archivo supera 2000 líneas");
        for (String lineaCruda : lineas) {
            if (lineaCruda.length() > 2000) throw new CamposInvalidosException("Una línea supera 2000 caracteres");
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
                if (nombre == null || nombre.isEmpty()) {
                    continue;
                }

                try {
                    LocalTime horaInicio = LocalTime.parse(horario.group(1), FORMATO_HORA);
                    LocalTime horaFin = LocalTime.parse(horario.group(2), FORMATO_HORA);
                    if (!horaFin.isAfter(horaInicio)) {
                        continue;
                    }
                    cursos.add(new CursoParseado(nombre, diaActual, horaInicio, horaFin));
                    if (cursos.size() > 500) throw new CamposInvalidosException("No se permiten más de 500 cursos por importación");
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
        return linea
                .replace("\uFEFF", "")
                .replace('\u00A0', ' ')
                .replaceAll("[\u2010-\u2015\u2212]", "-")
                .trim();
    }

    private String limpiarNombre(String nombre) {
        String limpio = nombre.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        limpio = PREFIJO_DIA.matcher(limpio).replaceFirst("").trim();
        limpio = PATRON_CODIGO_CURSO.matcher(limpio).replaceFirst("").trim();
        int fin = limpio.length();
        while (fin > 0 && !Character.isLetterOrDigit(limpio.codePointBefore(fin))) {
            fin -= Character.charCount(limpio.codePointBefore(fin));
        }
        return limpio.substring(0, fin).trim();
    }

    private String capitalizar(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1).toLowerCase();
    }
}
