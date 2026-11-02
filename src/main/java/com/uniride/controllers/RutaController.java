package com.uniride.controllers;

import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.dto.responses.MisRutasResumenRespuesta;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/rutas")
@PreAuthorize("hasRole('CONDUCTOR')")
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
        Page<RutaRespuesta> rutas = rutaService.listarMisRutas(estado, page, size);
        if (rutas.isEmpty()) {
            // Mantener el contrato Page existente y anexar mensaje orientativo.
            return ResponseEntity.ok()
                    .header("X-UniRide-Mensaje", mensajeVacio(estado))
                    .body(rutas);
        }
        return ResponseEntity.ok(rutas);
    }

    /** US09: variante que incluye el mensaje dentro del JSON para el frontend. */
    @GetMapping("/resumen")
    public ResponseEntity<MisRutasResumenRespuesta> resumenMisRutas(
            @RequestParam(required = false) EstadoRuta estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<RutaRespuesta> rutas = rutaService.listarMisRutas(estado, page, size);
        return ResponseEntity.ok(new MisRutasResumenRespuesta(
                rutas, rutas.isEmpty() ? mensajeVacio(estado) : null));
    }

    private String mensajeVacio(EstadoRuta estado) {
        if (estado == null) {
            return "Aun no tienes rutas visibles. Publica una ruta o revisa tus rutas eliminadas.";
        }
        return "No tienes rutas con estado " + estado
                + ". Prueba otro filtro o publica una nueva ruta.";
    }

    @PutMapping("/{id}")
    public ResponseEntity<RutaRespuesta> editarRuta(@PathVariable Long id,
            @Valid @RequestBody RutaRequest request,
            @RequestParam(defaultValue = "false") boolean confirmarImpacto) {
        return ResponseEntity.ok(rutaService.actualizar(id, request, confirmarImpacto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarRuta(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean confirmar) {
        rutaService.eliminar(id, confirmar);
        return ResponseEntity.noContent().build();
    }
}
