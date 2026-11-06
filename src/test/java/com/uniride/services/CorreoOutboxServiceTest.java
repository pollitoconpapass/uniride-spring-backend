package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.uniride.entities.CorreoPendiente;
import com.uniride.repositories.CorreoPendienteRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class CorreoOutboxServiceTest {
    @Test void otpVencidoOReemplazadoSeDescartaSinSmtp() {
        for (boolean vencido : List.of(true,false)) {
            var repo = mock(CorreoPendienteRepository.class);
            var smtp = mock(JavaMailSender.class);
            Instant ahora = Instant.parse("2026-11-05T15:00:00Z");
            var servicio = new CorreoOutboxService(repo, smtp, Clock.fixed(ahora,ZoneOffset.UTC),
                    Base64.getEncoder().encodeToString("clave-ficticia-exclusiva-de-prueba".getBytes()), "avisos@example.test");
            var usuarios = mock(com.uniride.repositories.UsuarioRepository.class);
            org.springframework.test.util.ReflectionTestUtils.setField(servicio,"usuarios",usuarios);
            var correo = new CorreoPendiente(); correo.setId(1L); correo.setOtpUsuarioId(2L);
            correo.setOtpHash("hash-anterior"); correo.setSiguienteIntento(ahora);
            correo.setExpira(vencido ? ahora : ahora.plusSeconds(900)); correo.setContenidoCifrado("cifrado");
            when(repo.bloquear(1L)).thenReturn(Optional.of(correo));
            when(usuarios.bloquearOtpParaEntrega(2L)).thenReturn(Optional.of(com.uniride.entities.Usuario.builder()
                    .id(2L).codigoVerificacion(vencido ? "hash-anterior" : "hash-nuevo").build()));
            servicio.entregar(1L);
            assertThat(correo.getDescartado()).isEqualTo(ahora);
            assertThat(correo.getEnviado()).isNull();
            assertThat(correo.getContenidoCifrado()).isEmpty();
            verifyNoInteractions(smtp);
        }
    }
    @Test void smtpFallaSeReintentaSinDuplicarFilaNiReenviarEntregado() {
        var repo = mock(CorreoPendienteRepository.class);
        var smtp = mock(JavaMailSender.class);
        var clock = mock(Clock.class);
        Instant ahora = Instant.parse("2026-11-05T15:00:00Z");
        when(clock.instant()).thenReturn(ahora);
        var servicio = new CorreoOutboxService(repo, smtp, clock,
                Base64.getEncoder().encodeToString("clave-ficticia-exclusiva-de-prueba".getBytes()), "avisos@example.test");
        when(repo.save(any())).thenAnswer(i -> { CorreoPendiente c = i.getArgument(0); c.setId(1L); return c; });
        servicio.encolar(4L, "ana@upc.edu.pe", "Verifica", "Código 123456");
        var captor = org.mockito.ArgumentCaptor.forClass(CorreoPendiente.class);
        verify(repo).save(captor.capture());
        var correo = captor.getValue();
        assertThat(correo.getContenidoCifrado()).doesNotContain("123456", "Código");
        when(repo.bloquear(1L)).thenReturn(Optional.of(correo));
        doThrow(new IllegalStateException("simulado")).doNothing().when(smtp).send(any(SimpleMailMessage.class));
        servicio.entregar(1L);
        assertThat(correo.getIntentos()).isEqualTo(1);
        assertThat(correo.getEnviado()).isNull();
        servicio.entregar(1L); // Todavía en enfriamiento.
        when(clock.instant()).thenReturn(ahora.plusSeconds(60));
        servicio.entregar(1L);
        servicio.entregar(1L);
        assertThat(correo.getEnviado()).isEqualTo(ahora.plusSeconds(60));
        assertThat(correo.getContenidoCifrado()).isEmpty();
        verify(smtp, times(2)).send(any(SimpleMailMessage.class));
    }
}
