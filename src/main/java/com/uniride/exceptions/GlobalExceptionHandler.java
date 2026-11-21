package com.uniride.exceptions;

import com.uniride.dto.responses.ErrorRespuesta;
import com.uniride.services.MensajeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final MensajeService mensajeService;

    public GlobalExceptionHandler(MensajeService mensajeService) {
        this.mensajeService = mensajeService;
    }

    @ExceptionHandler(DemasiadasSolicitudesException.class)
    public ResponseEntity<ErrorRespuesta> demasiadosIntentos(DemasiadasSolicitudesException e) {
        return ResponseEntity.status(429).header("Retry-After", "900")
                .body(new ErrorRespuesta(e.getMessage(), null));
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ErrorRespuesta> rutaInexistente(Exception e) {
        return ResponseEntity.status(404).body(new ErrorRespuesta("Recurso no encontrado", null));
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<ErrorRespuesta> parametroFaltante(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorRespuesta("Faltan parámetros obligatorios de la solicitud", null));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorRespuesta> metodoNoSoportado(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).allow(e.getSupportedHttpMethods() == null
                ? new org.springframework.http.HttpMethod[0]
                : e.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new))
                .body(new ErrorRespuesta("Método HTTP no permitido", null));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorRespuesta> tipoNoSoportado(Exception e) {
        return ResponseEntity.status(415).body(new ErrorRespuesta("Tipo de contenido no soportado", null));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorRespuesta> recursoNoEncontrado(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorRespuesta(mensajeService.resolverMensaje(e.getMessage()), null));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorRespuesta> negocio(BusinessException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorRespuesta(mensajeService.resolverMensaje(e.getMessage()), null));
    }

    @ExceptionHandler(NoAutorizadoException.class)
    public ResponseEntity<ErrorRespuesta> noAutorizado(NoAutorizadoException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorRespuesta(mensajeService.resolverMensaje(e.getMessage()), null));
    }

    @ExceptionHandler(NoPermitidoException.class)
    public ResponseEntity<ErrorRespuesta> noPermitido(NoPermitidoException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorRespuesta(mensajeService.resolverMensaje(e.getMessage()), null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorRespuesta> parametroInvalido(MethodArgumentTypeMismatchException e) {
        String mensaje = mensajeService.obtenerMensaje("error.parametro_invalido", e.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorRespuesta(mensaje, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorRespuesta> cuerpoNoLegible(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        String mensaje = request.getRequestURI().endsWith("/perfil/cursos")
                ? "El cuerpo JSON es inválido. Envía una lista de cursos y comprueba "
                        + "nombre, dia, horaInicio y horaFin; las horas deben tener formato HH:mm o HH:mm:ss."
                : mensajeService.obtenerMensaje("error.json_invalido");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorRespuesta(mensaje, null));
    }

    @ExceptionHandler(CamposInvalidosException.class)
    public ResponseEntity<ErrorRespuesta> camposInvalidos(CamposInvalidosException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorRespuesta(mensajeService.resolverMensaje(e.getMessage()), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuesta> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> campos.put(error.getField(), error.getDefaultMessage()));
        String mensaje = mensajeService.obtenerMensaje("error.validacion");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorRespuesta(mensaje, campos));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorRespuesta> archivoDemasiadoGrande(MaxUploadSizeExceededException e) {
        String mensaje = mensajeService.obtenerMensaje("error.archivo_demasiado_grande");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorRespuesta(mensaje, null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorRespuesta> accesoDenegado(AccessDeniedException e) {
        String mensaje = mensajeService.obtenerMensaje("error.acceso_denegado");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorRespuesta(mensaje, null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorRespuesta> conflictoIntegridad(DataIntegrityViolationException e) {
        log.warn("Operación rechazada por integridad referencial", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorRespuesta(
                        "No se pudo completar la operación porque existen datos relacionados "
                                + "o ya registrados.", null));
    }

    // El modelo de IA respondió texto en vez del JSON esperado.
    @ExceptionHandler(JacksonException.class)
    public ResponseEntity<ErrorRespuesta> respuestaAgenteInvalida(JacksonException e) {
        log.warn("El agente no devolvió el JSON esperado", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorRespuesta("El agente no pudo armar la respuesta, intenta de nuevo", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuesta> inesperada(Exception e) {
        // El cliente no debe recibir consultas SQL, nombres de tablas o detalles internos.
        log.error("Error interno no controlado", e);
        String mensaje = mensajeService.obtenerMensaje("error.inesperado",
                "Intenta nuevamente o contacta con soporte");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorRespuesta(mensaje, null));
    }
}
