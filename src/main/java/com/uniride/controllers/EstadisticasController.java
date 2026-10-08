package com.uniride.controllers;

import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.services.EstadisticasService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/estadisticas")
public class EstadisticasController {

    private final EstadisticasService estadisticasService;

    public EstadisticasController(EstadisticasService estadisticasService) {
        this.estadisticasService = estadisticasService;
    }

    @GetMapping
    public ResponseEntity<EstadisticasRespuesta> resumen() {
        return ResponseEntity.ok(estadisticasService.resumen());
    }

    @GetMapping("/semana")
    public ResponseEntity<AnalisisSemanalRespuesta> analisisSemanal() {
        return ResponseEntity.ok(estadisticasService.analisisSemanal());
    }

    @PreAuthorize("hasRole('CONDUCTOR')")
    @GetMapping("/rutas")
    public ResponseEntity<RankingRutasRespuesta> rankingRutas() {
        return ResponseEntity.ok(estadisticasService.rankingRutas());
    }
}
