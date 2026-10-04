package com.uniride.services;

import com.uniride.dto.responses.AuthRespuesta;
import com.uniride.dto.requests.LoginRequest;
import com.uniride.dto.requests.ReenviarCodigoRequest;
import com.uniride.dto.requests.RegistroRequest;
import com.uniride.dto.requests.VerificarCodigoRequest;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.security.JwtService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final int CODIGO_LARGO = 6;
    private static final int CODIGO_MAXIMO = 1_000_000;
    private static final int MINUTOS_EXPIRACION_CODIGO = 15;

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificacionService notificacionService;
    private final UsuarioMapper usuarioMapper;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService, NotificacionService notificacionService, UsuarioMapper usuarioMapper) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notificacionService = notificacionService;
        this.usuarioMapper = usuarioMapper;
    }

    @Transactional
    public AuthRespuesta registrar(RegistroRequest request) {
        if (usuarioRepository.existsByCorreoInstitucional(request.correo())) {
            Usuario existente = usuarioRepository.findByCorreoInstitucional(request.correo())
                    .orElseThrow(() -> new BusinessException("El correo ingresado ya se encuentra registrado"));
            notificacionService.notificar(existente, TipoNotificacion.ERROR, "Registro duplicado",
                    "Intentaste registrarte nuevamente, pero el correo ingresado ya se encuentra registrado.");
            throw new BusinessException("El correo ingresado ya se encuentra registrado");
        }

        if (usuarioRepository.existsByTelefono(request.telefono())) {
            throw new BusinessException("El teléfono ingresado ya se encuentra registrado");
        }

        String codigo = generarCodigo();

        Usuario usuario = Usuario.builder()
                .correoInstitucional(request.correo())
                .contrasenaHash(passwordEncoder.encode(request.contrasena()))
                .nombre(request.nombre())
                .apellidos(request.apellidos())
                .telefono(request.telefono())
                .rolPrincipal(request.rol())
                .aceptaTerminos(request.aceptaTerminos())
                .cuentaVerificada(false)
                .codigoVerificacion(codigo)
                .codigoVerificacionExpiracion(LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(MINUTOS_EXPIRACION_CODIGO))
                .build();

        Usuario guardado = usuarioRepository.save(usuario);

        notificacionService.notificar(guardado, TipoNotificacion.EXITO, "Código de verificación UniRide",
                "Hola " + guardado.getNombre() + ", tu código de verificación es: " + codigo
                        + ". El código vence en " + MINUTOS_EXPIRACION_CODIGO + " minutos.");

        return new AuthRespuesta(null,
                "Cuenta creada correctamente. Se envió un código de verificación a tu correo.",
                usuarioMapper.toUsuarioRespuesta(guardado));
    }

    @Transactional
    public AuthRespuesta iniciarSesion(LoginRequest request) {
        Optional<Usuario> opcional = usuarioRepository.findByCorreoInstitucional(request.correo());
        if (opcional.isEmpty()) {
            throw new NoAutorizadoException("Correo o contraseña incorrectos");
        }

        Usuario usuario = opcional.get();

        if (!passwordEncoder.matches(request.contrasena(), usuario.getContrasenaHash())) {
            notificacionService.notificar(usuario, TipoNotificacion.ERROR, "Inicio de sesión fallido",
                    "Se intentó iniciar sesión en tu cuenta con credenciales incorrectas.");
            throw new NoAutorizadoException("Correo o contraseña incorrectos");
        }

        if (!usuario.isCuentaVerificada()) {
            throw new BusinessException(
                    "Tu cuenta no ha sido verificada. Revisa tu correo para obtener el código de verificación.");
        }

        if (usuario.getRolPrincipal() == null) {
            throw new BusinessException("Debes seleccionar tu rol principal para ingresar al dashboard.");
        }

        String token = jwtService.generarToken(usuario.getCorreoInstitucional());

        return new AuthRespuesta(token, "Inicio de sesión exitoso",
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    @Transactional
    public AuthRespuesta verificarCodigo(VerificarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException("No existe ninguna cuenta con ese correo"));

        if (usuario.isCuentaVerificada()) {
            notificacionService.notificar(usuario, TipoNotificacion.ADVERTENCIA, "Cuenta ya verificada",
                    "La acción ya estaba completada: tu cuenta ya había sido verificada.");
            throw new BusinessException("Tu cuenta ya había sido verificada");
        }

        if (usuario.getCodigoVerificacion() == null || usuario.getCodigoVerificacionExpiracion() == null
                || usuario.getCodigoVerificacionExpiracion().isBefore(LocalDateTime.now(ZoneId.systemDefault()))) {
            throw new BusinessException("El código de verificación ha expirado. Solicita un nuevo código.");
        }

        if (!usuario.getCodigoVerificacion().equals(request.codigo())) {
            throw new CamposInvalidosException("El código de verificación es incorrecto");
        }

        usuario.setCuentaVerificada(true);
        usuario.setCodigoVerificacion(null);
        usuario.setCodigoVerificacionExpiracion(null);
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Cuenta verificada",
                "Tu cuenta fue verificada correctamente. Ya puedes iniciar sesión en UniRide.");

        return new AuthRespuesta(null,
                "Cuenta verificada correctamente. Ya puedes iniciar sesión.",
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    @Transactional
    public AuthRespuesta reenviarCodigo(ReenviarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException("No existe ninguna cuenta con ese correo"));

        if (usuario.isCuentaVerificada()) {
            throw new BusinessException("Tu cuenta ya había sido verificada");
        }

        String codigo = generarCodigo();
        usuario.setCodigoVerificacion(codigo);
        usuario.setCodigoVerificacionExpiracion(LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(MINUTOS_EXPIRACION_CODIGO));
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Nuevo código de verificación UniRide",
                "Hola " + usuario.getNombre() + ", tu nuevo código de verificación es: " + codigo
                        + ". El código vence en " + MINUTOS_EXPIRACION_CODIGO + " minutos.");

        return new AuthRespuesta(null,
                "Se envió un nuevo código de verificación a tu correo.",
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    private String generarCodigo() {
        return String.format("%0" + CODIGO_LARGO + "d", random.nextInt(CODIGO_MAXIMO));
    }
}
