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

    @org.springframework.beans.factory.annotation.Autowired
    private java.time.Clock clock = java.time.Clock.system(com.uniride.config.TiempoConfig.LIMA);

    private static final int CODIGO_LARGO = 6;
    private static final int CODIGO_MAXIMO = 1_000_000;
    private static final int MINUTOS_EXPIRACION_CODIGO = 15;

    @org.springframework.beans.factory.annotation.Autowired
    private com.uniride.security.LimiteAutenticacion limite = new com.uniride.security.LimiteAutenticacion();
    @org.springframework.beans.factory.annotation.Value("${uniride.auth.otp-intentos:5}")
    private int maxIntentosOtp = 5;
    @org.springframework.beans.factory.annotation.Value("${uniride.auth.otp-bloqueo-minutos:15}")
    private int bloqueoMinutos = 15;
    @org.springframework.beans.factory.annotation.Value("${uniride.auth.otp-reenvio-segundos:60}")
    private long reenvioSegundos = 60;

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificacionService notificacionService;
    private final UsuarioMapper usuarioMapper;
    private final MensajeService mensajeService;
    private final PerfilRolService perfilRolService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService, NotificacionService notificacionService, UsuarioMapper usuarioMapper,
            MensajeService mensajeService, PerfilRolService perfilRolService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notificacionService = notificacionService;
        this.usuarioMapper = usuarioMapper;
        this.mensajeService = mensajeService;
        this.perfilRolService = perfilRolService;
    }

    @Transactional
    public AuthRespuesta registrar(RegistroRequest request) {
        limite.cuenta(request.correo());
        validarLongitudContrasena(request.contrasena());
        if (usuarioRepository.existsByCorreoInstitucional(request.correo())) {
            Usuario existente = usuarioRepository.findByCorreoInstitucional(request.correo())
                    .orElseThrow(() -> new BusinessException(mensajeService.obtenerMensaje("auth.error.correo_registrado")));
            notificacionService.notificarErrorAutenticacion(existente,
                    mensajeService.obtenerMensaje("auth.notif.registro_duplicado.titulo"),
                    mensajeService.obtenerMensaje("auth.notif.registro_duplicado.mensaje"));
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.correo_registrado"));
        }

        if (usuarioRepository.existsByTelefono(com.uniride.util.ValidacionUtil.normalizarTelefono(request.telefono()))) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.telefono_registrado"));
        }

        String codigo = generarCodigo();

        Usuario usuario = Usuario.builder()
                .correoInstitucional(request.correo())
                .contrasenaHash(passwordEncoder.encode(request.contrasena()))
                .nombre(request.nombre())
                .apellidos(request.apellidos())
                .telefono(com.uniride.util.ValidacionUtil.normalizarTelefono(request.telefono()))
                .rolPrincipal(request.rol())
                .aceptaTerminos(request.aceptaTerminos())
                .cuentaVerificada(false)
                .codigoVerificacion(passwordEncoder.encode(codigo))
                .ultimoEnvioOtp(LocalDateTime.now(clock))
                .codigoVerificacionExpiracion(LocalDateTime.now(clock).plusMinutes(MINUTOS_EXPIRACION_CODIGO))
                .build();

        Usuario guardado = usuarioRepository.save(usuario);
        perfilRolService.asegurarPerfil(guardado, guardado.getRolPrincipal());

        notificacionService.notificarCodigo(guardado,
                mensajeService.obtenerMensaje("auth.notif.codigo_verificacion.titulo"),
                mensajeService.obtenerMensaje("auth.notif.codigo_verificacion.mensaje",
                        guardado.getNombre(), codigo, MINUTOS_EXPIRACION_CODIGO));

        return new AuthRespuesta(null,
                mensajeService.obtenerMensaje("auth.registro_exitoso"),
                usuarioMapper.toUsuarioRespuesta(guardado));
    }

    @Transactional
    public AuthRespuesta iniciarSesion(LoginRequest request) {
        limite.cuenta(request.correo());
        validarLongitudContrasena(request.contrasena());
        Optional<Usuario> opcional = usuarioRepository.findByCorreoInstitucional(request.correo());
        if (opcional.isEmpty()) {
            throw new NoAutorizadoException(mensajeService.obtenerMensaje("auth.error.credenciales_incorrectas"));
        }

        Usuario usuario = opcional.get();

        if (!passwordEncoder.matches(request.contrasena(), usuario.getContrasenaHash())) {
            notificacionService.notificarErrorAutenticacion(usuario,
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

    @Transactional(noRollbackFor = CamposInvalidosException.class)
    public AuthRespuesta verificarCodigo(VerificarCodigoRequest request) {
        limite.cuenta(request.correo());
        Usuario usuario = usuarioRepository.bloquearPorCorreo(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException(mensajeService.obtenerMensaje("auth.error.cuenta_no_encontrada")));

        LocalDateTime ahora = LocalDateTime.now(clock);
        if (usuario.getOtpBloqueadoHasta() != null && usuario.getOtpBloqueadoHasta().isAfter(ahora)) {
            throw new com.uniride.exceptions.DemasiadasSolicitudesException();
        }
        if (usuario.getOtpBloqueadoHasta() != null) {
            usuario.setIntentosOtp(0);
            usuario.setOtpBloqueadoHasta(null);
        }
        if (usuario.isCuentaVerificada()) {
            // No insertar en REQUIRES_NEW mientras esta transacción bloquea Usuario:
            // el FK de Notificacion esperaría la liberación del mismo bloqueo.
            Runnable aviso = () -> notificacionService.notificarAccionYaRealizada(usuario, "Cuenta ya verificada",
                    "La acción ya estaba completada: tu cuenta ya había sido verificada.");
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int estado) { aviso.run(); }
                        });
            } else aviso.run();
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.cuenta_ya_verificada"));
        }

        if (usuario.getCodigoVerificacion() == null || usuario.getCodigoVerificacionExpiracion() == null
                || !usuario.getCodigoVerificacionExpiracion().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.codigo_expirado"));
        }

        String almacenado = usuario.getCodigoVerificacion();
        // Compatibilidad de desafíos previos: código en claro solo hasta expirar (15 min).
        boolean correcto = almacenado.startsWith("$2")
                ? passwordEncoder.matches(request.codigo(), almacenado)
                : java.security.MessageDigest.isEqual(almacenado.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        request.codigo().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!correcto) {
            usuario.setIntentosOtp(usuario.getIntentosOtp() + 1);
            if (usuario.getIntentosOtp() >= maxIntentosOtp) {
                usuario.setOtpBloqueadoHasta(ahora.plusMinutes(bloqueoMinutos));
            }
            usuarioRepository.save(usuario);
            throw new CamposInvalidosException(mensajeService.obtenerMensaje("auth.error.codigo_incorrecto"));
        }

        usuario.setIntentosOtp(0);
        usuario.setOtpBloqueadoHasta(null);
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
        limite.cuenta(request.correo());
        Usuario usuario = usuarioRepository.bloquearPorCorreo(request.correo())
                .orElseThrow(() -> new ResourceNotFoundException(mensajeService.obtenerMensaje("auth.error.cuenta_no_encontrada")));

        if (usuario.isCuentaVerificada()) {
            throw new BusinessException(mensajeService.obtenerMensaje("auth.error.cuenta_ya_verificada"));
        }

        LocalDateTime ahora = LocalDateTime.now(clock);
        if ((usuario.getOtpBloqueadoHasta() != null && usuario.getOtpBloqueadoHasta().isAfter(ahora))
                || (usuario.getUltimoEnvioOtp() != null
                && usuario.getUltimoEnvioOtp().plusSeconds(reenvioSegundos).isAfter(ahora))) {
            throw new com.uniride.exceptions.DemasiadasSolicitudesException();
        }
        usuario.setUltimoEnvioOtp(ahora);
        usuario.setIntentosOtp(0);
        usuario.setOtpBloqueadoHasta(null);
        String codigo = generarCodigo();
        usuario.setCodigoVerificacion(passwordEncoder.encode(codigo));
        usuario.setCodigoVerificacionExpiracion(LocalDateTime.now(clock).plusMinutes(MINUTOS_EXPIRACION_CODIGO));
        usuarioRepository.save(usuario);

        notificacionService.notificarCodigo(usuario,
                mensajeService.obtenerMensaje("auth.notif.nuevo_codigo.titulo"),
                mensajeService.obtenerMensaje("auth.notif.nuevo_codigo.mensaje",
                        usuario.getNombre(), codigo, MINUTOS_EXPIRACION_CODIGO));

        return new AuthRespuesta(null,
                mensajeService.obtenerMensaje("auth.reenviar_exitoso"),
                null);
    }

    private void validarLongitudContrasena(String contrasena) {
        if (contrasena == null || contrasena.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new CamposInvalidosException("La contraseña debe tener como máximo 72 bytes UTF-8");
        }
    }

    private String generarCodigo() {
        return String.format("%0" + CODIGO_LARGO + "d", random.nextInt(CODIGO_MAXIMO));
    }
}
