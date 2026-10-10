package com.uniride.controllers;

import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.dto.requests.PerfilRequest;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.services.PerfilService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/perfil")
public class PerfilController {

    private final PerfilService perfilService;

    public PerfilController(PerfilService perfilService) {
        this.perfilService = perfilService;
    }

    @GetMapping
    public ResponseEntity<PerfilRespuesta> obtenerPerfil() {
        return ResponseEntity.ok(perfilService.obtenerPerfil());
    }

    @PreAuthorize("hasRole('CONDUCTOR')")
    @GetMapping("/usuario/{usuarioId}")
    public ResponseEntity<PerfilRespuesta> perfilDeUsuario(@PathVariable Long usuarioId) {
        return ResponseEntity.ok(perfilService.perfilDeUsuario(usuarioId));
    }

    @PutMapping
    public ResponseEntity<PerfilRespuesta> guardarDatosPersonales(
            @Valid @RequestBody PerfilRequest request) {
        return ResponseEntity.ok(perfilService.guardarDatosPersonales(request));
    }

    @PutMapping("/info-adicional")
    public ResponseEntity<PerfilRespuesta> guardarInfoAdicional(
            @Valid @RequestBody InfoAdicionalRequest request) {
        return ResponseEntity.ok(perfilService.guardarInfoAdicional(request));
    }
}
