package com.uniride.exceptions;

import com.uniride.dto.responses.ErrorRespuesta;
import com.uniride.services.MensajeService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MensajeService mensajeService;

    public GlobalExceptionHandler(MensajeService mensajeService) {
        this.mensajeService = mensajeService;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuesta> inesperada(Exception e) {
        String mensaje = mensajeService.obtenerMensaje("error.inesperado", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorRespuesta(mensaje, null));
    }
}
