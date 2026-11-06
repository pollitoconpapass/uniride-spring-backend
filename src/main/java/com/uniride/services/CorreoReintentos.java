package com.uniride.services;

import com.uniride.repositories.CorreoPendienteRepository;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CorreoReintentos {
    private final CorreoPendienteRepository repositorio;
    private final CorreoOutboxService servicio;
    private final Clock clock;
    public CorreoReintentos(CorreoPendienteRepository repositorio, CorreoOutboxService servicio, Clock clock) {
        this.repositorio = repositorio; this.servicio = servicio; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${uniride.mail.reintento-ms:30000}", scheduler = "correoScheduler")
    public void reenviar() {
        for (Long id : repositorio.pendientes(clock.instant(), PageRequest.of(0, 50))) servicio.entregar(id);
    }
}
