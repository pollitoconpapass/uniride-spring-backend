package com.uniride.agent.app.confirmation;

import com.uniride.agent.app.dto.ConfirmacionResumen;
import com.uniride.dto.responses.ErrorRespuesta;
import com.uniride.exceptions.CamposInvalidosException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/confirmaciones")
@RequiredArgsConstructor
public class ConfirmationController {
    private final ConfirmationService confirmations;

    @ExceptionHandler({org.springframework.transaction.TransactionException.class,
            org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.RecoverableDataAccessException.class,
            org.springframework.orm.jpa.JpaSystemException.class})
    public ResponseEntity<ErrorRespuesta> resultadoIncierto(RuntimeException failure) {
        return ResponseEntity.status(503).body(new ErrorRespuesta(
                "No se pudo determinar el resultado de la confirmación. Consulta su estado con el mismo identificador antes de reintentar. No prepares otra operación.",
                Map.of("codigo", "CONFIRMATION_RESULT_UNCERTAIN")));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConfirmacionResumen> obtener(@PathVariable UUID id) {
        return respuesta(confirmations.obtener(id));
    }

    @PostMapping("/{id}/confirmar")
    public ResponseEntity<ConfirmacionResumen> confirmar(@PathVariable UUID id,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        sinParametros(body, request);
        return respuesta(confirmations.confirmar(id));
    }

    @PostMapping("/{id}/cancelar")
    public ResponseEntity<ConfirmacionResumen> cancelar(@PathVariable UUID id,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        sinParametros(body, request);
        return respuesta(confirmations.cancelar(id));
    }

    private void sinParametros(String body, HttpServletRequest request) {
        if ((body != null && !body.isBlank()) || !request.getParameterMap().isEmpty())
            throw new CamposInvalidosException("La confirmación utiliza únicamente el identificador de la operación. No admite parámetros ni cuerpo.");
    }

    private ResponseEntity<ConfirmacionResumen> respuesta(ConfirmacionResumen response) {
        return ResponseEntity.status(response.estado() == EstadoConfirmacion.EXPIRADA ? 410 : 200).body(response);
    }
}
