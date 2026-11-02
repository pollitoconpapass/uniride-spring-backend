package com.uniride.controllers;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.dto.responses.BusquedaResumenRespuesta;
import com.uniride.services.BusquedaService;
import java.time.LocalTime;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/viajes")
@PreAuthorize("hasRole('PASAJERO')")
public class BusquedaController {

    private final BusquedaService busquedaService;

    public BusquedaController(BusquedaService busquedaService) {
        this.busquedaService = busquedaService;
    }

    @GetMapping("/buscar")
    public ResponseEntity<Page<BusquedaRespuesta>> buscar(
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String destino,
            @RequestParam(required = false) String dia,
            @RequestParam(required = false) LocalTime hora,
            @RequestParam(defaultValue = "false") boolean cercania,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<BusquedaRespuesta> resultados = busquedaService.buscar(
                origen, destino, dia, hora, cercania, page, size);
        if (resultados.isEmpty()) {
            return ResponseEntity.ok()
                    .header("X-UniRide-Mensaje", mensajeVacio(resultados))
                    .body(resultados);
        }
        return ResponseEntity.ok(resultados);
    }

    /** Variante US10: mensaje y sugerencia incluidos en JSON sin romper el endpoint original. */
    @GetMapping("/buscar/resumen")
    public ResponseEntity<BusquedaResumenRespuesta> buscarResumen(
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String destino,
            @RequestParam(required = false) String dia,
            @RequestParam(required = false) LocalTime hora,
            @RequestParam(defaultValue = "false") boolean cercania,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<BusquedaRespuesta> resultados = busquedaService.buscar(
                origen, destino, dia, hora, cercania, page, size);
        return ResponseEntity.ok(new BusquedaResumenRespuesta(
                resultados,
                resultados.isEmpty() ? mensajeVacio(resultados) : null,
                resultados.isEmpty() && resultados.getTotalElements() == 0
                        ? "Prueba ampliar los criterios: elimina algún filtro de origen, destino, día, hora o cercanía."
                        : null));
    }

    private String mensajeVacio(Page<BusquedaRespuesta> resultados) {
        return resultados.getTotalElements() > 0
                ? "Esta página no contiene viajes; prueba una página anterior."
                : "No se encontraron viajes compatibles con tus filtros.";
    }
}
