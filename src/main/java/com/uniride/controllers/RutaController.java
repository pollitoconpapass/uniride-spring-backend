package com.uniride.controllers;

import com.uniride.dto.RutaRequest;
import com.uniride.dto.RutaRespuesta;
import com.uniride.enums.EstadoRuta;
import com.uniride.services.RutaService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rutas")
public class RutaController {

    private final RutaService rutaService;

    public RutaController(RutaService rutaService) {
        this.rutaService = rutaService;
    }

    @PostMapping
    public ResponseEntity<RutaRespuesta> publicarRuta(@Valid @RequestBody RutaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rutaService.publicar(request));
    }

    @GetMapping
    public ResponseEntity<Page<RutaRespuesta>> listarMisRutas(
            @RequestParam(required = false) EstadoRuta estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(rutaService.listarMisRutas(estado, page, size));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RutaRespuesta> editarRuta(@PathVariable Long id,
            @Valid @RequestBody RutaRequest request) {
        return ResponseEntity.ok(rutaService.actualizar(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarRuta(@PathVariable Long id) {
        rutaService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
