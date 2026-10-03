package com.uniride.services;

import com.uniride.dto.AuthRespuesta;
import com.uniride.dto.LoginRequest;
import com.uniride.dto.ReenviarCodigoRequest;
import com.uniride.dto.RegistroRequest;
import com.uniride.dto.VerificarCodigoRequest;
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
        if (usuarioRepository.existsByCorreoInstitucional(request.getCorreo())) {
            Usuario existente = usuarioRepository.findByCorreoInstitucional(request.getCorreo())
                    .orElseThrow(() -> new BusinessException("El correo ingresado ya se encuentra registrado"));
            notificacionService.notificar(existente, TipoNotificacion.ERROR, "Registro duplicado",
                    "Intentaste registrarte nuevamente, pero el correo ingresado ya se encuentra registrado.");
            throw new BusinessException("El correo ingresado ya se encuentra registrado");
        }

        if (usuarioRepository.existsByTelefono(request.getTelefono())) {
            throw new BusinessException("El teléfono ingresado ya se encuentra registrado");
        }

        String codigo = generarCodigo();

        Usuario usuario = Usuario.builder()
                .correoInstitucional(request.getCorreo())
                .contrasenaHash(passwordEncoder.encode(request.getContrasena()))
                .nombre(request.getNombre())
                .apellidos(request.getApellidos())
                .telefono(request.getTelefono())
                .rolPrincipal(request.getRol())
                .aceptaTerminos(request.isAceptaTerminos())
                .cuentaVerificada(false)
                .codigoVerificacion(codigo)
                .codigoVerificacionExpiracion(LocalDateTime.now().plusMinutes(MINUTOS_EXPIRACION_CODIGO))
                .build();

        Usuario guardado = usuarioRepository.save(usuario);

        notificacionService.notificar(guardado, TipoNotificacion.EXITO, "Código de verificación UniRide",
                "Hola " + guardado.getNombre() + ", tu código de verificación es: " + codigo
                        + ". El código vence en " + MINUTOS_EXPIRACION_CODIGO + " minutos.");

        return AuthRespuesta.builder()
                .mensaje("Cuenta creada correctamente. Se envió un código de verificación a tu correo.")
                .usuario(usuarioMapper.toUsuarioRespuesta(guardado))
                .build();
    }

    @Transactional
    public AuthRespuesta iniciarSesion(LoginRequest request) {
        Optional<Usuario> opcional = usuarioRepository.findByCorreoInstitucional(request.getCorreo());
        if (opcional.isEmpty()) {
            throw new NoAutorizadoException("Correo o contraseña incorrectos");
        }

        Usuario usuario = opcional.get();

        if (!passwordEncoder.matches(request.getContrasena(), usuario.getContrasenaHash())) {
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

        return AuthRespuesta.builder()
                .token(token)
                .mensaje("Inicio de sesión exitoso")
                .usuario(usuarioMapper.toUsuarioRespuesta(usuario))
                .build();
    }

    @Transactional
    public AuthRespuesta verificarCodigo(VerificarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.getCorreo())
                .orElseThrow(() -> new ResourceNotFoundException("No existe ninguna cuenta con ese correo"));

        if (usuario.isCuentaVerificada()) {
            notificacionService.notificar(usuario, TipoNotificacion.ADVERTENCIA, "Cuenta ya verificada",
                    "La acción ya estaba completada: tu cuenta ya había sido verificada.");
            throw new BusinessException("Tu cuenta ya había sido verificada");
        }

        if (usuario.getCodigoVerificacion() == null || usuario.getCodigoVerificacionExpiracion() == null
                || usuario.getCodigoVerificacionExpiracion().isBefore(LocalDateTime.now())) {
            throw new BusinessException("El código de verificación ha expirado. Solicita un nuevo código.");
        }

        if (!usuario.getCodigoVerificacion().equals(request.getCodigo())) {
            throw new CamposInvalidosException("El código de verificación es incorrecto");
        }

        usuario.setCuentaVerificada(true);
        usuario.setCodigoVerificacion(null);
        usuario.setCodigoVerificacionExpiracion(null);
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Cuenta verificada",
                "Tu cuenta fue verificada correctamente. Ya puedes iniciar sesión en UniRide.");

        return AuthRespuesta.builder()
                .mensaje("Cuenta verificada correctamente. Ya puedes iniciar sesión.")
                .usuario(usuarioMapper.toUsuarioRespuesta(usuario))
                .build();
    }

    @Transactional
    public AuthRespuesta reenviarCodigo(ReenviarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.getCorreo())
                .orElseThrow(() -> new ResourceNotFoundException("No existe ninguna cuenta con ese correo"));

        if (usuario.isCuentaVerificada()) {
            throw new BusinessException("Tu cuenta ya había sido verificada");
        }

        String codigo = generarCodigo();
        usuario.setCodigoVerificacion(codigo);
        usuario.setCodigoVerificacionExpiracion(LocalDateTime.now().plusMinutes(MINUTOS_EXPIRACION_CODIGO));
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Nuevo código de verificación UniRide",
                "Hola " + usuario.getNombre() + ", tu nuevo código de verificación es: " + codigo
                        + ". El código vence en " + MINUTOS_EXPIRACION_CODIGO + " minutos.");

        return AuthRespuesta.builder()
                .mensaje("Se envió un nuevo código de verificación a tu correo.")
                .usuario(usuarioMapper.toUsuarioRespuesta(usuario))
                .build();
    }

    private String generarCodigo() {
        return String.format("%0" + CODIGO_LARGO + "d", random.nextInt(CODIGO_MAXIMO));
    }
}
