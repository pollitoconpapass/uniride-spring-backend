package com.uniride.controllers;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.services.BusquedaService;
import java.time.LocalTime;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/viajes")
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
        return ResponseEntity.ok(busquedaService.buscar(origen, destino, dia, hora, cercania, page, size));
    }
}
