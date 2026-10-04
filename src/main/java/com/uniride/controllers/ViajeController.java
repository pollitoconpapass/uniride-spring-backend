package com.uniride.controllers;

import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.ViajeRespuesta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.services.ContribucionService;
import com.uniride.services.SolicitudService;
import com.uniride.services.ViajeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViajeController {

    private final ViajeService viajeService;
    private final SolicitudService solicitudService;
    private final ContribucionService contribucionService;

    public ViajeController(ViajeService viajeService, SolicitudService solicitudService,
            ContribucionService contribucionService) {
        this.viajeService = viajeService;
        this.solicitudService = solicitudService;
        this.contribucionService = contribucionService;
    }

    @PostMapping("/rutas/{rutaId}/viajes")
    public ResponseEntity<ViajeRespuesta> crearViaje(@PathVariable Long rutaId,
            @Valid @RequestBody ViajeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(viajeService.crear(rutaId, request));
    }

    @GetMapping("/rutas/{rutaId}/viajes")
    public ResponseEntity<Page<ViajeRespuesta>> listarViajes(@PathVariable Long rutaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(viajeService.listarPorRuta(rutaId, page, size));
    }

    @GetMapping("/viajes/{viajeId}/solicitudes")
    public ResponseEntity<Page<SolicitudRespuesta>> solicitudesRecibidas(
            @PathVariable Long viajeId,
            @RequestParam(required = false) EstadoSolicitud estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(solicitudService.listarRecibidas(viajeId, estado, page, size));
    }

    @GetMapping("/viajes/{id}/sugerencia-contribucion")
    public ResponseEntity<SugerenciaContribucionRespuesta> sugerenciaContribucion(
            @PathVariable Long id,
            @RequestParam(required = false) Integer pasajeros) {
        return ResponseEntity.ok(contribucionService.sugerencia(id, pasajeros));
    }

    @PutMapping("/viajes/{id}/confirmar")
    public ResponseEntity<ViajeRespuesta> confirmarViaje(@PathVariable Long id) {
        ViajeRespuesta respuesta = viajeService.confirmar(id);
        if (respuesta.estado() == EstadoViaje.VENCIDO) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
        }
        return ResponseEntity.ok(respuesta);
    }

    @PutMapping("/viajes/{id}/iniciar")
    public ResponseEntity<ViajeRespuesta> iniciarViaje(@PathVariable Long id) {
        return ResponseEntity.ok(viajeService.iniciar(id));
    }

    @PutMapping("/viajes/{id}/completar")
    public ResponseEntity<ViajeRespuesta> completarViaje(@PathVariable Long id) {
        return ResponseEntity.ok(viajeService.completar(id));
    }

    @PutMapping("/viajes/{id}/cancelar")
    public ResponseEntity<ViajeRespuesta> cancelarViaje(@PathVariable Long id) {
        return ResponseEntity.ok(viajeService.cancelar(id));
    }

    @DeleteMapping("/viajes/{id}")
    public ResponseEntity<Void> eliminarViaje(@PathVariable Long id) {
        viajeService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
