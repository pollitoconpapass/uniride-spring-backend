package com.uniride.controllers;

import com.uniride.dto.MetodoCompensacionRequest;
import com.uniride.dto.MetodoCompensacionRespuesta;
import com.uniride.services.MetodoCompensacionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metodos-compensacion")
public class MetodoCompensacionController {

    private final MetodoCompensacionService metodoCompensacionService;

    public MetodoCompensacionController(MetodoCompensacionService metodoCompensacionService) {
        this.metodoCompensacionService = metodoCompensacionService;
    }

    @GetMapping
    public ResponseEntity<List<MetodoCompensacionRespuesta>> listar() {
        return ResponseEntity.ok(metodoCompensacionService.listar());
    }

    @PostMapping
    public ResponseEntity<MetodoCompensacionRespuesta> registrar(
            @Valid @RequestBody MetodoCompensacionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(metodoCompensacionService.registrar(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MetodoCompensacionRespuesta> actualizar(@PathVariable Long id,
            @Valid @RequestBody MetodoCompensacionRequest request) {
        return ResponseEntity.ok(metodoCompensacionService.actualizar(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> eliminar(@PathVariable Long id) {
        return ResponseEntity.ok(metodoCompensacionService.eliminar(id));
    }
}
