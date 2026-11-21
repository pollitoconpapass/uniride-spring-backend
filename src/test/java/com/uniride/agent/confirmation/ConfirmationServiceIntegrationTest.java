package com.uniride.agent.confirmation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import com.uniride.services.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Runs against the isolated test datasource only, with no model/provider calls. */
@SpringBootTest
class ConfirmationServiceIntegrationTest {
    @Autowired ConfirmationService service;
    @Autowired ConfirmationRepository confirmations;
    @Autowired UsuarioRepository users;
    @Autowired PerfilRepository profiles;
    @Autowired RutaRepository routes;
    @Autowired ViajeRepository trips;
    @Autowired SolicitudRepository requests;
    @Autowired MetodoCompensacionRepository methods;
    @Autowired PerfilRolService roles;
    @Autowired Clock clock;
    @MockitoBean NotificacionService notifications;
    Usuario driver;
    Usuario passenger;

    @BeforeEach void fixtures() {
        driver = user(Rol.CONDUCTOR);
        passenger = user(Rol.PASAJERO);
        profiles.save(Perfil.builder().usuario(driver).nombre("Ana").apellidos("Prueba")
                .carrera("Software").distrito("Surco").universidad("UPC").diasDisponibles("Lunes")
                .horariosDisponibles("08:00").fechaInicioClases(LocalDate.of(2026, 3, 1))
                .fechaFinClases(LocalDate.of(2026, 12, 20)).build());
        authenticate(driver);
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void preparesRouteWithoutBusinessWritesAndKeepsExactParameters() {
        long before = routes.count();
        var result = service.prepararRuta(routeRequest());

        assertThat(result.estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
        assertThat(result.id()).isNull();
        assertThat(result.confirmacion().id()).isNotNull();
        assertThat(result.confirmacion().estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(result.confirmacion().resumen()).contains("Surco", "UPC", "capacidad 4", "Parque");
        assertThat(result.confirmacion().parametros().path("origen").asText()).isEqualTo("Surco");
        assertThat(result.confirmacion().parametros().path("capacidadMaxima").asInt()).isEqualTo(4);
        assertThat(result.confirmacion().expira()).isAfter(clock.instant().plusSeconds(500));
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void confirmsOnceAndReplayReturnsSameCommittedReceipt() {
        var pending = service.prepararRuta(routeRequest()).confirmacion();
        long before = routes.count();

        var executed = service.confirmar(pending.id());
        var replay = service.confirmar(pending.id());

        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(replay).isEqualTo(executed);
        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void preparesAndExecutesTripUsingAnOwnedRoute() {
        Ruta route = route();
        var request = tripRequest();
        long before = trips.count();
        var pending = service.prepararViaje(route.getId(), request).confirmacion();
        assertThat(trips.count()).isEqualTo(before);
        assertThat(pending.resumen()).contains(request.fecha().toString(), request.hora().toString(), "UPC", "Surco");

        var executed = service.confirmar(pending.id());

        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(trips.count()).isEqualTo(before + 1);
        assertThat(trips.findById(executed.resultado().id()).orElseThrow().getFecha()).isEqualTo(request.fecha());
    }

    @Test void preparesAndExecutesPassengerRequestWithoutChangingCapacity() {
        Viaje trip = trip();
        authenticate(passenger);
        long before = requests.count();
        var pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.GASOLINA, null)).confirmacion();
        assertThat(requests.count()).isEqualTo(before);
        assertThat(pending.resumen()).contains("GASOLINA", "PENDIENTE");

        var executed = service.confirmar(pending.id());

        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(requests.count()).isEqualTo(before + 1);
        assertThat(requests.findById(executed.resultado().id()).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(trips.findById(trip.getId()).orElseThrow().getPasajeros()).isZero();
    }

    @Test void preparesAndExecutesCompensationMethodForPassenger() {
        authenticate(passenger);
        long before = methods.count();
        var pending = service.prepararMetodo(new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Compartir apuntes", true)).confirmacion();
        assertThat(methods.count()).isEqualTo(before);

        var executed = service.confirmar(pending.id());

        assertThat(methods.count()).isEqualTo(before + 1);
        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
    }

    @Test void passengerCannotPrepareDriverOperations() {
        authenticate(passenger);
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(NoPermitidoException.class);
        assertThatThrownBy(() -> service.prepararViaje(123L, tripRequest())).isInstanceOf(NoPermitidoException.class);
        verifyNoInteractions(notifications);
    }

    @Test void driverCannotPreparePassengerOperation() {
        assertThatThrownBy(() -> service.prepararSolicitud(123L,
                new SolicitudRequest(null, TipoCompensacion.DINERO, null))).isInstanceOf(NoPermitidoException.class);
    }

    @Test void unauthenticatedPreparationAndExecutionAreRejected() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(NoAutorizadoException.class);
    }

    @Test void anotherUserCannotReadCancelOrExecuteConfirmation() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        authenticate(passenger);
        assertThatThrownBy(() -> service.obtener(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelar(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void routeOfAnotherDriverCannotBePrepared() {
        Ruta other = route();
        authenticate(user(Rol.CONDUCTOR));
        assertThatThrownBy(() -> service.prepararViaje(other.getId(), tripRequest())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void roleChangeBeforeExecutionRevokesPermissionAndDoesNotWrite() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        driver.setRolPrincipal(Rol.PASAJERO);
        users.save(driver);
        // The SecurityContext still carries the old authority; DB role remains authoritative.
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(NoPermitidoException.class);
        assertThat(routes.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void expiredConfirmationPersistsExpiredStateWithoutExecution() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        var entity = confirmations.findById(pending).orElseThrow();
        entity.setCreada(clock.instant().minusSeconds(1200));
        entity.setExpira(clock.instant().minusSeconds(1));
        confirmations.save(entity);
        long before = routes.count();

        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EXPIRADA);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.EXPIRADA);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void cancelledConfirmationCannotExecuteAndCancellationIsIdempotent() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        assertThat(service.cancelar(pending).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThat(service.cancelar(pending).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cancelada");
        verifyNoInteractions(notifications);
    }

    @Test void executedConfirmationCannotBeCancelled() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        service.confirmar(pending);
        assertThatThrownBy(() -> service.cancelar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("ejecutada");
    }

    @Test void profileDestinationChangeRequiresNewPreparation() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        Perfil profile = profiles.findByUsuarioId(driver.getId()).orElseThrow();
        profile.setUniversidad("Otra sede"); profiles.save(profile);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verifyNoInteractions(notifications);
    }

    @Test void routeDetailsChangedAfterPreparationCannotBeSilentlyUsed() {
        Ruta route = route();
        UUID pending = service.prepararViaje(route.getId(), tripRequest()).confirmacion().id();
        route.setOrigen("Miraflores"); routes.save(route);
        long before = trips.count();
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        assertThat(trips.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void tripScheduleChangedAfterPreparationCannotBeSilentlyUsed() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        trip.setHora(trip.getHora().plusMinutes(30)); trips.save(trip);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        verifyNoInteractions(notifications);
    }

    @Test void businessRulesAreRevalidatedWhenCapacityWasConsumed() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        trip.setPasajeros(4); trips.save(trip);
        long before = requests.count();
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cupos");
        assertThat(requests.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void duplicateBusinessRequestIsRevalidatedWithoutSideEffectNotification() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        requests.save(Solicitud.builder().viaje(trip).pasajero(passenger)
                .preferenciaCompensacion(TipoCompensacion.DINERO).estado(EstadoSolicitud.PENDIENTE).build());
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("Ya enviaste");
        verifyNoInteractions(notifications);
    }

    @Test void validationFailureDoesNotCreatePendingOperation() {
        long before = confirmations.count();
        assertThatThrownBy(() -> service.prepararRuta(new RutaRequest("", 0, "", null, null)))
                .isInstanceOf(CamposInvalidosException.class);
        assertThat(confirmations.count()).isEqualTo(before);
    }

    @Test void transactionRollbackKeepsPendingAndAllowsExplicitSafeRetry() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        doThrow(new BusinessException("Fallo de prueba posterior a guardar ruta"))
                .when(notifications).notificar(any(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class);
        assertThat(routes.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(confirmations.findById(pending).orElseThrow().getResultado()).isNull();

        reset(notifications);
        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(routes.count()).isEqualTo(before + 1);
    }

    @Test void simultaneousConfirmationsExecuteExactlyOnce() throws Exception {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ConfirmacionResumen> confirm = () -> {
                authenticate(driver);
                try { start.await(); return service.confirmar(pending); }
                finally { SecurityContextHolder.clearContext(); }
            };
            Future<ConfirmacionResumen> first = pool.submit(confirm);
            Future<ConfirmacionResumen> second = pool.submit(confirm);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void pendingCountIsBoundedPerUser() {
        for (int i = 0; i < 20; i++) service.prepararRuta(routeRequest());
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(BusinessException.class).hasMessageContaining("20");
    }

    @Test void differentConfirmationsCannotRegisterTheSameMethodConcurrently() throws Exception {
        var request = new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Compartir apuntes", true);
        UUID firstId = service.prepararMetodo(request).confirmacion().id();
        UUID secondId = service.prepararMetodo(request).confirmacion().id();
        long before = methods.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<UUID, Callable<String>> confirmation = id -> () -> {
                authenticate(driver);
                try {
                    start.await();
                    return service.confirmar(id).estado().name();
                } catch (BusinessException conflict) {
                    assertThat(conflict).hasMessageContaining("mismo tipo y descripción");
                    return "CONFLICTO";
                } finally { SecurityContextHolder.clearContext(); }
            };
            Future<String> first = pool.submit(confirmation.apply(firstId));
            Future<String> second = pool.submit(confirmation.apply(secondId));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("EJECUTADA", "CONFLICTO");
        }
        assertThat(methods.count()).isEqualTo(before + 1);
        assertThat(List.of(service.obtener(firstId).estado(), service.obtener(secondId).estado()))
                .containsExactlyInAnyOrder(EstadoConfirmacion.EJECUTADA, EstadoConfirmacion.PENDIENTE);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    private Usuario user(Rol role) {
        Usuario user = users.save(Usuario.builder().correoInstitucional(UUID.randomUUID() + "@upc.edu.pe")
                .telefono("fixture-" + UUID.randomUUID()).nombre("Ana").apellidos("Prueba")
                .contrasenaHash("isolated-test-hash").rolPrincipal(role).aceptaTerminos(true).cuentaVerificada(true).build());
        roles.asegurarPerfil(user, role);
        return user;
    }

    private void authenticate(Usuario user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getCorreoInstitucional(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRolPrincipal()))));
    }

    private RutaRequest routeRequest() { return new RutaRequest("Surco", 4, "Parque", null, null); }
    private ViajeRequest tripRequest() {
        return new ViajeRequest(LocalDate.now(clock).plusDays(3), LocalTime.of(8, 0));
    }
    private Ruta route() {
        return routes.save(Ruta.builder().conductor(driver).origen("Surco").destino("UPC")
                .capacidadMaxima(4).puntosReferencia("Parque").estado(EstadoRuta.ACTIVA).build());
    }
    private Viaje trip() {
        return trips.save(Viaje.builder().ruta(route()).fecha(tripRequest().fecha()).hora(tripRequest().hora())
                .dia("Lunes").estado(EstadoViaje.PROGRAMADO).build());
    }
}
