package com.uniride.services;

import com.uniride.entities.Notificacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoNotificacion;
import com.uniride.repositories.NotificacionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
public class NotificacionService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CorreoOutboxService outbox;
    @org.springframework.beans.factory.annotation.Autowired
    private java.time.Clock clock = java.time.Clock.system(com.uniride.config.TiempoConfig.LIMA);
    private final java.util.Map<String, java.time.Instant> ultimosAvisos = new java.util.HashMap<>();

    private final NotificacionRepository notificacionRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String remitente;

    public NotificacionService(NotificacionRepository notificacionRepository, JavaMailSender mailSender) {
        this.notificacionRepository = notificacionRepository;
        this.mailSender = mailSender;
    }

    @Transactional
    public Notificacion notificar(Usuario usuario, TipoNotificacion tipo, String asunto, String mensaje) {
        return registrar(usuario, tipo, asunto, mensaje, mensaje, false);
    }

    @Transactional
    public Notificacion notificarCodigo(Usuario usuario, String asunto, String mensajeCorreo) {
        return registrar(usuario, TipoNotificacion.EXITO, asunto,
                "Se solicitó un código de verificación. Revisa tu correo institucional.", mensajeCorreo, true);
    }

    private Notificacion registrar(Usuario usuario, TipoNotificacion tipo, String asunto,
            String mensaje, String mensajeCorreo, boolean codigo) {
        Notificacion notificacion = Notificacion.builder()
                .usuario(usuario)
                .tipo(tipo)
                .mensaje(mensaje)
                .leida(false)
                .build();

        Notificacion guardada = notificacionRepository.save(notificacion);
        String destino = usuario.getCorreoInstitucional();
        if (outbox != null) {
            if (codigo) outbox.encolarOtp(guardada.getId(), usuario, asunto, mensajeCorreo);
            else outbox.encolar(guardada.getId(), destino, asunto, mensajeCorreo);
            // El scheduler consume únicamente filas confirmadas; no hay SMTP antes del commit.
            return guardada;
        }
        // Nunca enviar un correo por una operacion que posteriormente se revierta.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviarCorreo(destino, asunto, mensajeCorreo);
                }
            });
        } else {
            // Invocaciones sin transaccion activa: save() ya termino antes del envio.
            enviarCorreo(destino, asunto, mensajeCorreo);
        }
        return guardada;
    }

    /**
     * Una accion duplicada devuelve un 409 en el llamador. Su aviso debe persistir
     * aunque esa transaccion de negocio se revierta por la excepcion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notificacion notificarAccionYaRealizada(Usuario usuario, String asunto, String mensaje) {
        if (!admitirAviso(usuario, asunto)) return null;
        return notificar(usuario, TipoNotificacion.ADVERTENCIA, asunto, mensaje);
    }

    /** Auditoria 11.4: un error de autenticacion debe avisarse incluso si la
     * transaccion del login/registro se revierte al retornar 401/409. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notificacion notificarErrorAutenticacion(Usuario usuario, String asunto, String mensaje) {
        if (!admitirAviso(usuario, asunto)) return null;
        return notificar(usuario, TipoNotificacion.ERROR, asunto, mensaje);
    }

    private boolean admitirAviso(Usuario usuario, String asunto) {
        synchronized (ultimosAvisos) {
            java.time.Instant ahora = clock.instant();
            ultimosAvisos.values().removeIf(fecha -> fecha.plusSeconds(900).isBefore(ahora));
            String clave = usuario.getId() + ":" + asunto;
            if (ultimosAvisos.containsKey(clave) || ultimosAvisos.size() >= 10_000) return false;
            ultimosAvisos.put(clave, ahora);
            return true;
        }
    }

    private void enviarCorreo(String destino, String asunto, String mensaje) {
        if (remitente == null || remitente.isBlank()) {
            log.warn("Correo no configurado: establece EMAIL_ADDRESS y EMAIL_PASSWORD. "
                    + "La notificacion de {} quedo guardada en la plataforma.", destino);
            return;
        }
        if (destino == null || destino.isBlank()) {
            log.warn("No se puede enviar un correo sin destinatario: {}", asunto);
            return;
        }
        try {
            SimpleMailMessage correo = new SimpleMailMessage();
            correo.setFrom(remitente);
            correo.setTo(destino);
            correo.setSubject(asunto);
            correo.setText(mensaje);
            mailSender.send(correo);
        } catch (Exception e) {
            log.warn("No se pudo enviar el correo: {}", e.getClass().getSimpleName());
        }
    }
}
