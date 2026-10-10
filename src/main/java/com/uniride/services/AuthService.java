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
    private final MensajeService mensajeService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService, NotificacionService notificacionService, UsuarioMapper usuarioMapper,
            MensajeService mensajeService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notificacionService = notificacionService;
        this.usuarioMapper = usuarioMapper;
        this.mensajeService = mensajeService;
    }

    @Transactional
    public AuthRespuesta registrar(RegistroRequest request) {
        if (usuarioRepository.existsByCorreoInstitucional(request.correo())) {
            Usuario existente = usuarioRepository.findByCorreoInstitucional(request.correo())
                    .orElseThrow(() -> new BusinessException(mensajeService.obtenerMensaje("auth.error.correo_registrado")));
            notificacionService.notificar(existente, TipoNotificacion.ERROR,
                    mensajeService.obtenerMensaje("auth.notif.registro_duplicado.titulo"),
                    mensajeService.obtenerMensaje("auth.notif.registro_duplicado.mensaje"));
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.correo_registrado"));
        }

        if (usuarioRepository.existsByTelefono(request.telefono())) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.telefono_registrado"));
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

        notificacionService.notificar(guardado, TipoNotificacion.EXITO,
                mensajeService.obtenerMensaje("auth.notif.codigo_verificacion.titulo"),
                mensajeService.obtenerMensaje("auth.notif.codigo_verificacion.mensaje",
                        guardado.getNombre(), codigo, MINUTOS_EXPIRACION_CODIGO));

        return new AuthRespuesta(null,
                mensajeService.obtenerMensaje("auth.registro_exitoso"),
                usuarioMapper.toUsuarioRespuesta(guardado));
    }

    @Transactional
    public AuthRespuesta iniciarSesion(LoginRequest request) {
        Optional<Usuario> opcional = usuarioRepository.findByCorreoInstitucional(request.correo());
        if (opcional.isEmpty()) {
            throw new NoAutorizadoException(mensajeService.obtenerMensaje("auth.error.credenciales_incorrectas"));
        }

        Usuario usuario = opcional.get();

        if (!passwordEncoder.matches(request.contrasena(), usuario.getContrasenaHash())) {
            notificacionService.notificar(usuario, TipoNotificacion.ERROR,
                    mensajeService.obtenerMensaje("auth.notif.login_fallido.titulo"),
                    mensajeService.obtenerMensaje("auth.notif.login_fallido.mensaje"));
            throw new NoAutorizadoException(mensajeService.obtenerMensaje("auth.error.credenciales_incorrectas"));
        }

        if (!usuario.isCuentaVerificada()) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.cuenta_no_verificada"));
        }

        if (usuario.getRolPrincipal() == null) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.rol_no_seleccionado"));
        }

        String token = jwtService.generarToken(usuario.getCorreoInstitucional());

        return new AuthRespuesta(token,
                mensajeService.obtenerMensaje("auth.login_exitoso"),
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    @Transactional
    public AuthRespuesta verificarCodigo(VerificarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException(mensajeService.obtenerMensaje("auth.error.cuenta_no_encontrada")));

        if (usuario.isCuentaVerificada()) {
            notificacionService.notificar(usuario, TipoNotificacion.ADVERTENCIA, "Cuenta ya verificada",
                    "La acción ya estaba completada: tu cuenta ya había sido verificada.");
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.cuenta_ya_verificada"));
        }

        if (usuario.getCodigoVerificacion() == null || usuario.getCodigoVerificacionExpiracion() == null
                || usuario.getCodigoVerificacionExpiracion().isBefore(LocalDateTime.now(ZoneId.systemDefault()))) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.codigo_expirado"));
        }

        if (!usuario.getCodigoVerificacion().equals(request.codigo())) {
            throw new CamposInvalidosException(mensajeService.obtenerMensaje("auth.error.codigo_incorrecto"));
        }

        usuario.setCuentaVerificada(true);
        usuario.setCodigoVerificacion(null);
        usuario.setCodigoVerificacionExpiracion(null);
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                mensajeService.obtenerMensaje("auth.notif.cuenta_verificada.titulo"),
                mensajeService.obtenerMensaje("auth.notif.cuenta_verificada.mensaje"));

        return new AuthRespuesta(null,
                mensajeService.obtenerMensaje("auth.verificar_exitoso"),
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    @Transactional
    public AuthRespuesta reenviarCodigo(ReenviarCodigoRequest request) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException(mensajeService.obtenerMensaje("auth.error.cuenta_no_encontrada")));

        if (usuario.isCuentaVerificada()) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.cuenta_ya_verificada"));
        }

        String codigo = generarCodigo();
        usuario.setCodigoVerificacion(codigo);
        usuario.setCodigoVerificacionExpiracion(LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(MINUTOS_EXPIRACION_CODIGO));
        usuarioRepository.save(usuario);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                mensajeService.obtenerMensaje("auth.notif.nuevo_codigo.titulo"),
                mensajeService.obtenerMensaje("auth.notif.nuevo_codigo.mensaje",
                        usuario.getNombre(), codigo, MINUTOS_EXPIRACION_CODIGO));

        return new AuthRespuesta(null,
                mensajeService.obtenerMensaje("auth.reenviar_exitoso"),
                usuarioMapper.toUsuarioRespuesta(usuario));
    }

    private String generarCodigo() {
        return String.format("%0" + CODIGO_LARGO + "d", random.nextInt(CODIGO_MAXIMO));
    }
}
