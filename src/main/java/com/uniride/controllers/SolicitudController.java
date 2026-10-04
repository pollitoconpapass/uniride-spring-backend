package com.uniride.controllers;

import com.uniride.dto.requests.RechazarMultipleRequest;
import com.uniride.dto.requests.RechazarRequest;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.services.SolicitudService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/solicitudes")
public class SolicitudController {

    private final SolicitudService solicitudService;

    public SolicitudController(SolicitudService solicitudService) {
        this.solicitudService = solicitudService;
    }

    @PostMapping("/viajes/{viajeId}")
    public ResponseEntity<SolicitudRespuesta> solicitar(@PathVariable Long viajeId,
            @Valid @RequestBody SolicitudRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(solicitudService.crear(viajeId, request));
    }

    @GetMapping
    public ResponseEntity<Page<SolicitudRespuesta>> misSolicitudes(
            @RequestParam(required = false) EstadoSolicitud estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(solicitudService.listarMisSolicitudes(estado, page, size));
    }

    @PutMapping("/{id}/cancelar")
    public ResponseEntity<SolicitudRespuesta> cancelar(@PathVariable Long id) {
        return ResponseEntity.ok(solicitudService.cancelar(id));
    }

    @PutMapping("/{id}/aceptar")
    public ResponseEntity<SolicitudRespuesta> aceptar(@PathVariable Long id) {
        return ResponseEntity.ok(solicitudService.aceptar(id));
    }

    @PutMapping("/{id}/rechazar")
    public ResponseEntity<SolicitudRespuesta> rechazar(@PathVariable Long id,
            @RequestBody(required = false) RechazarRequest request) {
        return ResponseEntity.ok(solicitudService.rechazar(id, request));
    }

    @PutMapping("/rechazar-multiples")
    public ResponseEntity<List<SolicitudRespuesta>> rechazarMultiple(
            @Valid @RequestBody RechazarMultipleRequest request) {
        return ResponseEntity.ok(solicitudService.rechazarMultiple(request));
    }
}
