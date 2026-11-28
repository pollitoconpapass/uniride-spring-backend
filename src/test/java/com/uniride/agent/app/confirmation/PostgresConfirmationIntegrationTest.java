package com.uniride.agent.app.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import com.uniride.entities.Solicitud;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Repeats the transaction/ownership/replay contracts with real PostgreSQL row locks and Flyway. */
@Tag("postgres")
@ActiveProfiles("postgres")
class PostgresConfirmationIntegrationTest extends ConfirmationServiceIntegrationTest {
    @Test void deletionWaitsForAnUncommittedAssociationAndRechecksAfterCommit() throws Exception {
        var method = metodo(passenger, TipoCompensacion.FAVOR, "Apuntes", true);
        var trip = trip();
        authenticate(passenger);
        var pending = service.prepararEliminacionMetodo(null, "Apuntes").confirmacion().id();
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var confirmStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var insertion = pool.submit(() -> transaction.executeWithoutResult(status -> {
                requests.saveAndFlush(Solicitud.builder().viaje(trip).pasajero(passenger)
                        .preferenciaCompensacion(TipoCompensacion.FAVOR).estado(EstadoSolicitud.CANCELADA)
                        .metodoCompensacion(method).build());
                inserted.countDown();
                await(commit);
            }));
            try {
                await(inserted);
                var confirmation = pool.submit(() -> {
                    authenticate(passenger);
                    try {
                        confirmStarted.countDown();
                        return service.confirmar(pending);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
                await(confirmStarted);
                assertThatThrownBy(() -> confirmation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();
                insertion.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> confirmation.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class)
                        .hasStackTraceContaining("solicitudes asociadas");
            } finally {
                commit.countDown();
            }
        }
        assertThat(methods.findById(method.getId())).isPresent();
        assertThat(service.obtener(pending).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verifyNoInteractions(notifications);
    }
}
