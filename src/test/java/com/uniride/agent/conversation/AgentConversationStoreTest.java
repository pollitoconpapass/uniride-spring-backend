package com.uniride.agent.conversation;

import com.uniride.agent.conversation.AgentConversationStore;
import com.uniride.agent.conversation.CompensationIntent;
import com.uniride.agent.support.AgentProviderException;

import static org.assertj.core.api.Assertions.*;
import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.confirmation.TipoOperacion;
import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.ConfirmacionResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;

class AgentConversationStoreTest {
    private final MutableClock clock = new MutableClock();
    private final AgentConversationStore store = new AgentConversationStore(clock);

    @Test void conversationsAreBoundToOwnerAndSeparateSessions() {
        UUID first;
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) {
            first = lease.id(); lease.complete("Mi ruta privada", "Consulta preparada");
        }
        try (var lease = store.open(1L, Rol.CONDUCTOR, first)) {
            assertThat(lease.history()).hasSize(2);
            assertThat(((UserMessage) lease.history().getFirst()).getText()).isEqualTo("Mi ruta privada");
        }
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) { assertThat(lease.history()).isEmpty(); }
        assertThatThrownBy(() -> store.open(2L, Rol.CONDUCTOR, first))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void windowIsBoundedAndSessionHasLifetimeRequestLimit() {
        UUID id;
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) { id = lease.id(); }
        for (int i = 1; i < 100; i++) {
            try (var lease = store.open(1L, Rol.CONDUCTOR, id)) { lease.complete("mensaje " + i, "respuesta"); }
        }
        assertThatThrownBy(() -> store.open(1L, Rol.CONDUCTOR, id))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_LIMIT"));
        // Different user/session starts without inheriting either history or limit.
        try (var lease = store.open(2L, Rol.PASAJERO, null)) { assertThat(lease.history()).isEmpty(); }
    }

    @Test void onlyTwentyMessagesAreRetained() {
        UUID id;
        try (var lease = store.open(1L, Rol.PASAJERO, null)) { id = lease.id(); }
        for (int i = 0; i < 15; i++) {
            try (var lease = store.open(1L, Rol.PASAJERO, id)) { lease.complete("mensaje " + i, "respuesta"); }
        }
        try (var lease = store.open(1L, Rol.PASAJERO, id)) {
            assertThat(lease.history()).hasSize(20);
            assertThat(((UserMessage) lease.history().getFirst()).getText()).isEqualTo("mensaje 5");
            assertThat(lease.providerHistory()).hasSize(8);
            assertThat(lease.providerHistory().getFirst().getText()).isEqualTo("mensaje 11");
            assertThat(lease.providerHistory().get(6).getText()).isEqualTo("mensaje 14");
        }
    }

    @Test void expirationRestartAndRoleChangeDiscardContext() {
        UUID expired;
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) { expired = lease.id(); }
        clock.instant = clock.instant.plus(Duration.ofMinutes(30));
        store.cleanup();
        assertThatThrownBy(() -> store.open(1L, Rol.CONDUCTOR, expired)).isInstanceOf(AgentProviderException.class);
        UUID changed;
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) { changed = lease.id(); }
        assertThatThrownBy(() -> store.open(1L, Rol.PASAJERO, changed))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_ROLE_CHANGED"));
        assertThatThrownBy(() -> new AgentConversationStore(clock).open(1L, Rol.CONDUCTOR, changed))
                .isInstanceOf(AgentProviderException.class);
    }

    @Test void draftAndConfirmationReferenceStayWithTheAuthorizedSession() {
        var draft = new CompensationIntent.Registro(TipoCompensacion.FAVOR, null);
        Instant expiry = clock.instant.plus(Duration.ofMinutes(10));
        var pending = new ConfirmacionResumen(UUID.randomUUID(), TipoOperacion.ELIMINAR_METODO,
                EstadoConfirmacion.PENDIENTE, "Eliminar Yape.", null, expiry, null);
        UUID id;
        try (var lease = store.open(1L, Rol.PASAJERO, null)) {
            id = lease.id();
            lease.guardarBorrador(draft);
            lease.complete("Elimina mi método Yape", new ChatResponse("Revisa la operación.", List.of(), List.of(),
                    List.of(), new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Revisa la operación.", pending)));
        }
        assertThatThrownBy(() -> store.open(2L, Rol.PASAJERO, id))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        try (var lease = store.open(1L, Rol.PASAJERO, id)) {
            assertThat(lease.borrador()).isEqualTo(draft);
            assertThat(lease.confirmacionPendiente()).isEqualTo(pending.id());
            assertThat(lease.confirmacionResuelta()).isNull();
        }
        assertThatThrownBy(() -> store.open(1L, Rol.CONDUCTOR, id))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_ROLE_CHANGED"));
        try (var lease = store.open(1L, Rol.CONDUCTOR, null)) {
            assertThat(lease.borrador()).isNull();
            assertThat(lease.confirmacionPendiente()).isNull();
            assertThat(lease.confirmacionResuelta()).isNull();
        }
    }

    @Test void simultaneousMessagesDoNotMixAndLeaseReleasesOnFailure() {
        UUID id;
        try (var lease = store.open(1L, Rol.PASAJERO, null)) {
            id = lease.id();
            assertThatThrownBy(() -> store.open(1L, Rol.PASAJERO, id))
                    .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("CONVERSATION_BUSY"));
        }
        try (var lease = store.open(1L, Rol.PASAJERO, id)) { assertThat(lease.history()).isEmpty(); }
    }

    @Test void perUserSessionsEvictOldestIdleConversation() {
        UUID first;
        try (var lease = store.open(1L, Rol.PASAJERO, null)) { first = lease.id(); }
        for (int i = 0; i < 5; i++) {
            clock.instant = clock.instant.plusSeconds(1);
            try (var lease = store.open(1L, Rol.PASAJERO, null)) { assertThat(lease.history()).isEmpty(); }
        }
        assertThatThrownBy(() -> store.open(1L, Rol.PASAJERO, first)).isInstanceOf(AgentProviderException.class);
    }

    @Test void credentialsAndOversizedMessagesAreRejectedBeforeProviderAndMemory() {
        for (String message : new String[]{"", "a".repeat(1001), "Bearer simulated-secret", "password=secret", "GROQ_API_KEY=testsecret",
                "Mi contraseña es Fixture123!", "My password is Fixture123!", "{\"password\":\"fixture\"}"})
            assertThatThrownBy(() -> AgentConversationStore.validateMessage(message)).isInstanceOf(AgentProviderException.class);
        assertThatCode(() -> AgentConversationStore.validateMessage("Consulta mis rutas")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-11-21T15:00:00Z");
        public ZoneId getZone() { return ZoneId.of("America/Lima"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
