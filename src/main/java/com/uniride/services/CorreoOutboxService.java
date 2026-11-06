package com.uniride.services;

import com.uniride.entities.CorreoPendiente;
import com.uniride.repositories.CorreoPendienteRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Slf4j
public class CorreoOutboxService {
    private final CorreoPendienteRepository repositorio;
    private final JavaMailSender emisor;
    private final Clock clock;
    private final SecretKeySpec clave;
    private final String remitente;
    @org.springframework.beans.factory.annotation.Autowired
    private com.uniride.repositories.UsuarioRepository usuarios;

    public CorreoOutboxService(CorreoPendienteRepository repositorio, JavaMailSender emisor, Clock clock,
            @Value("${uniride.mail.outbox-key:${uniride.jwt.secret}}") String clave,
            @Value("${spring.mail.username:}") String remitente) {
        this.repositorio = repositorio; this.emisor = emisor; this.clock = clock;
        this.remitente = remitente;
        try {
            this.clave = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getDecoder().decode(clave)), "AES");
        } catch (GeneralSecurityException e) { throw new IllegalStateException("No se pudo preparar el cifrado", e); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long encolar(Long notificacionId, String destino, String asunto, String mensaje) {
        return preparar(notificacionId, destino, asunto, mensaje, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long encolarOtp(Long notificacionId, com.uniride.entities.Usuario usuario, String asunto, String mensaje) {
        return preparar(notificacionId, usuario.getCorreoInstitucional(), asunto, mensaje, usuario);
    }

    private Long preparar(Long notificacionId, String destino, String asunto, String mensaje,
            com.uniride.entities.Usuario usuario) {
        CorreoPendiente correo = new CorreoPendiente();
        correo.setNotificacionId(notificacionId); correo.setDestino(destino); correo.setAsunto(asunto);
        correo.setContenidoCifrado(cifrar(mensaje)); correo.setSiguienteIntento(clock.instant());
        if (usuario != null) {
            correo.setOtpUsuarioId(usuario.getId()); correo.setOtpHash(usuario.getCodigoVerificacion());
            correo.setExpira(usuario.getCodigoVerificacionExpiracion()
                    .atZone(com.uniride.config.TiempoConfig.LIMA).toInstant());
        }
        return repositorio.save(correo).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void entregar(Long id) {
        CorreoPendiente correo = repositorio.bloquear(id).orElse(null);
        if (correo == null || correo.getEnviado() != null || correo.getDescartado() != null
                || correo.getSiguienteIntento().isAfter(clock.instant())) return;
        if (correo.getOtpUsuarioId() != null) {
            var usuario = usuarios.bloquearOtpParaEntrega(correo.getOtpUsuarioId()).orElse(null);
            if (!correo.getExpira().isAfter(clock.instant()) || usuario == null || usuario.isCuentaVerificada()
                    || !Objects.equals(correo.getOtpHash(), usuario.getCodigoVerificacion())) {
                correo.setDescartado(clock.instant()); correo.setContenidoCifrado("");
                repositorio.save(correo); return;
            }
        }
        if (remitente == null || remitente.isBlank()) return;
        try {
            SimpleMailMessage mensaje = new SimpleMailMessage();
            mensaje.setFrom(remitente); mensaje.setTo(correo.getDestino()); mensaje.setSubject(correo.getAsunto());
            mensaje.setText(descifrar(correo.getContenidoCifrado()));
            emisor.send(mensaje);
            correo.setEnviado(clock.instant());
            // Eliminar el cuerpo tras la entrega, especialmente si contenía OTP.
            correo.setContenidoCifrado("");
        } catch (Exception e) {
            correo.setIntentos(correo.getIntentos() + 1);
            long segundos = Math.min(3600, 30L << Math.min(correo.getIntentos(), 6));
            correo.setSiguienteIntento(clock.instant().plusSeconds(segundos));
            log.warn("Entrega SMTP pendiente; outbox={} intento={} tipo={}", id,
                    correo.getIntentos(), e.getClass().getSimpleName());
        }
        repositorio.save(correo);
    }

    private String cifrar(String texto) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(128, iv));
            byte[] bytes = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + bytes.length).put(iv).put(bytes).array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("No se pudo cifrar el correo", e); }
    }

    private String descifrar(String texto) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(texto);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
        return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    }
}
