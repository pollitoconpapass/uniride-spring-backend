package com.uniride.controllers;

import com.uniride.dto.AuthRespuesta;
import com.uniride.dto.LoginRequest;
import com.uniride.dto.ReenviarCodigoRequest;
import com.uniride.dto.RegistroRequest;
import com.uniride.dto.VerificarCodigoRequest;
import com.uniride.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/registro")
    public ResponseEntity<AuthRespuesta> registrar(@Valid @RequestBody RegistroRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.registrar(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthRespuesta> iniciarSesion(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.iniciarSesion(request));
    }

    @PostMapping("/verificar")
    public ResponseEntity<AuthRespuesta> verificar(@Valid @RequestBody VerificarCodigoRequest request) {
        return ResponseEntity.ok(authService.verificarCodigo(request));
    }

    @PostMapping("/reenviar-codigo")
    public ResponseEntity<AuthRespuesta> reenviar(@Valid @RequestBody ReenviarCodigoRequest request) {
        return ResponseEntity.ok(authService.reenviarCodigo(request));
    }
}
