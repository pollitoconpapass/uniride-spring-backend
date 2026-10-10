package com.uniride.controllers;

import com.uniride.dto.responses.AuthRespuesta;
import com.uniride.dto.requests.LoginRequest;
import com.uniride.dto.requests.ReenviarCodigoRequest;
import com.uniride.dto.requests.RegistroRequest;
import com.uniride.dto.requests.VerificarCodigoRequest;
import com.uniride.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(security = {})
    @PostMapping("/registro")
    public ResponseEntity<AuthRespuesta> registrar(@Valid @RequestBody RegistroRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.registrar(request));
    }

    @Operation(security = {})
    @PostMapping("/login")
    public ResponseEntity<AuthRespuesta> iniciarSesion(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.iniciarSesion(request));
    }

    @Operation(security = {})
    @PostMapping("/verificar")
    public ResponseEntity<AuthRespuesta> verificar(@Valid @RequestBody VerificarCodigoRequest request) {
        return ResponseEntity.ok(authService.verificarCodigo(request));
    }

    @Operation(security = {})
    @PostMapping("/reenviar-codigo")
    public ResponseEntity<AuthRespuesta> reenviar(@Valid @RequestBody ReenviarCodigoRequest request) {
        return ResponseEntity.ok(authService.reenviarCodigo(request));
    }
}
