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

@Service
@Slf4j
public class NotificacionService {

    private final NotificacionRepository notificacionRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String remitente;

    public NotificacionService(NotificacionRepository notificacionRepository, JavaMailSender mailSender) {
        this.notificacionRepository = notificacionRepository;
        this.mailSender = mailSender;
    }

    public Notificacion notificar(Usuario usuario, TipoNotificacion tipo, String asunto, String mensaje) {
        Notificacion notificacion = Notificacion.builder()
                .usuario(usuario)
                .tipo(tipo)
                .mensaje(mensaje)
                .leida(false)
                .build();

        Notificacion guardada = notificacionRepository.save(notificacion);
        enviarCorreo(usuario.getCorreoInstitucional(), asunto, mensaje);
        return guardada;
    }

    private void enviarCorreo(String destino, String asunto, String mensaje) {
        try {
            SimpleMailMessage correo = new SimpleMailMessage();
            correo.setFrom(remitente);
            correo.setTo(destino);
            correo.setSubject(asunto);
            correo.setText(mensaje);
            mailSender.send(correo);
        } catch (Exception e) {
            log.warn("No se pudo enviar el correo a {}: {}", destino, e.getMessage());
        }
    }
}
