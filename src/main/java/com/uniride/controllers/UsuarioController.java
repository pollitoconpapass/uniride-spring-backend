package com.uniride.controllers;

import com.uniride.dto.requests.CambiarRolRequest;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.services.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping("/me")
    public ResponseEntity<UsuarioRespuesta> obtenerUsuarioActual() {
        return ResponseEntity.ok(usuarioService.obtenerUsuarioActual());
    }

    @PutMapping("/rol")
    public ResponseEntity<UsuarioRespuesta> cambiarRol(@Valid @RequestBody CambiarRolRequest request) {
        return ResponseEntity.ok(usuarioService.cambiarRol(request));
    }
}
