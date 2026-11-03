package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.uniride.entities.Notificacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoNotificacion;
import com.uniride.repositories.NotificacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
class NotificacionUs25ServiceTest {

    @Mock NotificacionRepository repositorio;
    @Mock JavaMailSender emisor;
    private NotificacionService servicio;
    private Usuario usuario;

    @BeforeEach
    void preparar() {
        servicio = new NotificacionService(repositorio, emisor);
        ReflectionTestUtils.setField(servicio, "remitente", "avisos@uniride.example");
        usuario = Usuario.builder().id(10L).correoInstitucional("alumno@upc.edu.pe").build();
    }

    @Test
    void exitoPersisteNotificacionYPreparaCorreo() {
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        Notificacion n = servicio.notificar(usuario, TipoNotificacion.EXITO,
                "Viaje creado", "El viaje fue publicado");
        assertThat(n.getTipo()).isEqualTo(TipoNotificacion.EXITO);
        assertThat(n.getUsuario()).isEqualTo(usuario);
        verify(emisor).send(any(SimpleMailMessage.class));
    }

    @Test
    void errorDeSmtpNoImpideGuardarNotificacion() {
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new IllegalStateException("SMTP no disponible"))
                .when(emisor).send(any(SimpleMailMessage.class));
        Notificacion n = servicio.notificar(usuario, TipoNotificacion.EXITO,
                "Viaje creado", "Registrado");
        assertThat(n.getMensaje()).isEqualTo("Registrado");
        verify(repositorio).save(any(Notificacion.class));
    }

    @Test
    void sinCorreoConfiguradoGuardaPeroNoIntentaEnviar() {
        ReflectionTestUtils.setField(servicio, "remitente", "");
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        servicio.notificar(usuario, TipoNotificacion.EXITO, "Registrado", "Registrado");
        verify(repositorio).save(any(Notificacion.class));
        verifyNoInteractions(emisor);
    }

    @Test
    void accionRepetidaRegistraAvisoDeAdvertencia() {
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        Notificacion n = servicio.notificarAccionYaRealizada(usuario,
                "Solicitud ya registrada", "No se creo otra solicitud");
        assertThat(n.getTipo()).isEqualTo(TipoNotificacion.ADVERTENCIA);
        verify(emisor).send(any(SimpleMailMessage.class));
    }

    @Test
    void enviaCorreoSoloDespuesDeConfirmarTransaccion() {
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            servicio.notificar(usuario, TipoNotificacion.EXITO, "Registrado", "OK");
            verifyNoInteractions(emisor);
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(emisor).send(any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void noEnviaCorreoSiTransaccionSeRevierte() {
        when(repositorio.save(any(Notificacion.class))).thenAnswer(i -> i.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            servicio.notificar(usuario, TipoNotificacion.EXITO, "Registrado", "OK");
            // Se descartan las sincronizaciones al simular el rollback.
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(emisor);
    }
}
