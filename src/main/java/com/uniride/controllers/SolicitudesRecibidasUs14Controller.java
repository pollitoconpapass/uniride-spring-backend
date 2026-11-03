package com.uniride.controllers;

import com.uniride.dto.responses.DetalleSolicitudRecibidaUs14Respuesta;
import com.uniride.dto.responses.SolicitudesRecibidasUs14ResumenRespuesta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.services.SolicitudesRecibidasUs14Service;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/solicitudes/recibidas")
@PreAuthorize("hasRole('CONDUCTOR')")
public class SolicitudesRecibidasUs14Controller {

    private final SolicitudesRecibidasUs14Service service;

    public SolicitudesRecibidasUs14Controller(SolicitudesRecibidasUs14Service service) {
        this.service = service;
    }

    @GetMapping("/por-ruta")
    public ResponseEntity<SolicitudesRecibidasUs14ResumenRespuesta> porRuta(
            @RequestParam(required = false) EstadoSolicitud estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(service.porRuta(estado, page, size));
    }

    @GetMapping("/{solicitudId}/detalle")
    public ResponseEntity<DetalleSolicitudRecibidaUs14Respuesta> detalle(
            @PathVariable Long solicitudId) {
        return ResponseEntity.ok(service.detalle(solicitudId));
    }
}
