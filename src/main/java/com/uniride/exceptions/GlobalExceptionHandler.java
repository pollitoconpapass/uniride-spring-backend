package com.uniride.exceptions;

import com.uniride.dto.ErrorRespuesta;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorRespuesta> recursoNoEncontrado(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorRespuesta.builder().mensaje(e.getMessage()).build());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorRespuesta> negocio(BusinessException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorRespuesta.builder().mensaje(e.getMessage()).build());
    }

    @ExceptionHandler(NoAutorizadoException.class)
    public ResponseEntity<ErrorRespuesta> noAutorizado(NoAutorizadoException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorRespuesta.builder().mensaje(e.getMessage()).build());
    }

    @ExceptionHandler(CamposInvalidosException.class)
    public ResponseEntity<ErrorRespuesta> camposInvalidos(CamposInvalidosException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorRespuesta.builder().mensaje(e.getMessage()).build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuesta> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> campos.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorRespuesta.builder().mensaje("Errores de validación").campos(campos).build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorRespuesta> archivoDemasiadoGrande(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorRespuesta.builder()
                        .mensaje("El archivo no puede superar un tamaño de 1 MB")
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuesta> inesperada(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorRespuesta.builder().mensaje("Error inesperado: " + e.getMessage()).build());
    }
}
