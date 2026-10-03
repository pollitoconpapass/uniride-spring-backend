package com.uniride.controllers;

import com.uniride.dto.ViajeRequest;
import com.uniride.dto.ViajeRespuesta;
import com.uniride.services.ViajeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViajeController {

    private final ViajeService viajeService;

    public ViajeController(ViajeService viajeService) {
        this.viajeService = viajeService;
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

    @DeleteMapping("/viajes/{id}")
    public ResponseEntity<Void> eliminarViaje(@PathVariable Long id) {
        viajeService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
