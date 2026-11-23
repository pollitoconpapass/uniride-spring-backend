package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.dto.ContribucionFiltros;
import com.uniride.agent.dto.ContribucionResultado.Estado;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.agent.tools.ContribucionAgentTools;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.mappers.*;
import com.uniride.repositories.*;
import com.uniride.services.ContribucionService;
import com.uniride.services.PerfilRolService;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest
@Import({AgentQueryService.class, AgentQueryPersistenceTest.QueryConfig.class,
        ContribucionAgentTools.class, ContribucionService.class, PerfilRolService.class})
class AgentQueryPersistenceTest {
    @Autowired AgentQueryService queries;
    @Autowired UsuarioRepository users;
    @Autowired RutaRepository routes;
    @Autowired ViajeRepository trips;
    @Autowired MetodoCompensacionRepository methods;
    @Autowired SolicitudRepository applications;
    @Autowired PerfilRolService roles;
    @Autowired EntityManager em;
    @Autowired ContribucionAgentTools contributionTool;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean UsuarioService current;
    @MockitoBean SolicitudService requests;

    @Test void identificaAmbiguedadGlobalAunqueCoincidenciasSuperenLasPrimerasCienRutas() {
        Usuario owner = user(Rol.CONDUCTOR);
        for (int i = 0; i < 105; i++) route(owner, "Origen " + i, EstadoRuta.ACTIVA);
        Ruta first = route(owner, "Surco", EstadoRuta.ACTIVA);
        Ruta second = route(owner, "Surco Norte", EstadoRuta.ACTIVA);
        route(user(Rol.CONDUCTOR), "Surco ajeno", EstadoRuta.ACTIVA);
        when(current.usuarioActual()).thenReturn(owner);

        var matches = queries.identificarRutas("Surco", "UPC");

        assertThat(matches.getTotalElements()).isEqualTo(2);
        assertThat(matches.getContent()).extracting(r -> r.id()).containsExactly(second.getId(), first.getId());
        assertThat(queries.rutas(10).items()).hasSize(7);
        assertThat(queries.rutas(0).hayMas()).isTrue();
        assertThat(queries.rutas(10).hayMas()).isFalse();
    }

    @Test void fechaSeFiltraAntesDelLimiteSinFalsosNoEncontradosNiUnicidadFalsa() {
        Usuario owner = user(Rol.CONDUCTOR);
        String origen = "Surco prueba de fecha";
        Ruta route = route(owner, origen, EstadoRuta.ACTIVA);
        for (int i = 0; i < 25; i++) trip(route, LocalDate.of(2026, 11, 22), LocalTime.of(8, 0));
        Viaje first = trip(route, LocalDate.of(2026, 12, 2), LocalTime.of(8, 0));
        Viaje second = trip(route, LocalDate.of(2026, 12, 2), LocalTime.of(9, 0));
        when(current.usuarioActual()).thenReturn(user(Rol.PASAJERO));

        var matches = queries.identificarViajes(origen, "UPC", null, LocalDate.of(2026, 12, 2), null);
        assertThat(matches.getTotalElements()).isEqualTo(2);
        assertThat(matches.getContent()).extracting(v -> v.viajeId()).containsExactly(first.getId(), second.getId());
        var unique = queries.identificarViajes(origen, "UPC", null, LocalDate.of(2026, 12, 2), LocalTime.of(9, 0));
        assertThat(unique.getTotalElements()).isEqualTo(1);
        assertThat(unique.getContent().getFirst().viajeId()).isEqualTo(second.getId());
        assertThat(queries.buscar(origen, null, null, null, null, 0).hayMas()).isTrue();
        assertThat(queries.buscar(origen, null, null, null, null, 2).items()).hasSize(7);
    }

    @Test void contribucionSoloIdentificaViajesPublicosDisponiblesOAceptadosDelUsuario() {
        var conductor = user(Rol.CONDUCTOR);
        var pasajero = user(Rol.PASAJERO);
        var otro = user(Rol.PASAJERO);
        String origen = "Surco prueba de acceso";
        var ruta = route(conductor, origen, EstadoRuta.ACTIVA);
        var rutaEliminada = route(conductor, origen, EstadoRuta.ELIMINADA);
        var fecha = LocalDate.of(2026, 11, 25);
        var hora = LocalTime.of(8, 0);
        var disponible = trip(ruta, fecha, hora);
        var llenoPropio = trip(ruta, fecha, hora);
        llenoPropio.setPasajeros(4);
        var llenoAjeno = trip(ruta, fecha, hora);
        llenoAjeno.setPasajeros(4);
        var cancelado = trip(ruta, fecha, hora);
        cancelado.setEstado(EstadoViaje.CANCELADO);
        var pasado = trip(ruta, LocalDate.of(2026, 11, 21), LocalTime.of(9, 59));
        var eliminado = trip(rutaEliminada, fecha, hora);
        application(pasajero, llenoPropio, EstadoSolicitud.ACEPTADA);
        application(otro, llenoAjeno, EstadoSolicitud.ACEPTADA);
        application(pasajero, llenoAjeno, EstadoSolicitud.PENDIENTE);
        application(pasajero, cancelado, EstadoSolicitud.ACEPTADA);
        application(pasajero, pasado, EstadoSolicitud.ACEPTADA);
        application(pasajero, eliminado, EstadoSolicitud.ACEPTADA);
        em.flush();
        em.clear();
        when(current.usuarioActual()).thenReturn(pasajero);

        var publicos = new ContribucionFiltros(origen, "upc", null, null, null, 1, null, false);
        var propios = new ContribucionFiltros(null, null, null, null, null, null, null, true);

        assertThat(queries.identificarViajes(publicos)).extracting(AgentQueryService.ViajeContribucion::id)
                .containsExactly(disponible.getId(), llenoPropio.getId());
        assertThat(queries.identificarViajes(propios)).extracting(AgentQueryService.ViajeContribucion::id)
                .containsExactly(llenoPropio.getId());
        when(current.usuarioActual()).thenReturn(otro);
        assertThat(queries.identificarViajes(propios)).extracting(AgentQueryService.ViajeContribucion::id)
                .containsExactly(llenoAjeno.getId());
    }

    @Test void contribucionFiltraConductorYUsaSelectoresLiteralesAntesDePaginar() {
        var ana = user(Rol.CONDUCTOR);
        var dalia = user(Rol.CONDUCTOR);
        dalia.setNombre("Dalia");
        var daliaLucia = user(Rol.CONDUCTOR);
        daliaLucia.setNombre("Dalia Lucía");
        var fecha = LocalDate.of(2026, 11, 25);
        var hora = LocalTime.of(8, 0);
        String origen = "Surco prueba literal";
        trip(route(ana, origen, EstadoRuta.ACTIVA), fecha, hora);
        trip(route(daliaLucia, origen + " 100X", EstadoRuta.ACTIVA), fecha, hora);
        var literal = trip(route(dalia, origen + " 100%", EstadoRuta.ACTIVA), fecha, hora);
        em.flush();
        em.clear();
        when(current.usuarioActual()).thenReturn(user(Rol.PASAJERO));

        var todos = new ContribucionFiltros(origen, "upc", "MIÉRCOLES", fecha, hora, 2, null, false);
        var porConductor = new ContribucionFiltros(origen, "upc", "MIÉRCOLES", fecha, hora, 2, " DALIA ", false);
        var porTexto = new ContribucionFiltros(origen + " 100%", "upc", "MIÉRCOLES", fecha, hora, 2, null, false);

        var ambiguo = queries.identificarViajes(todos);
        assertThat(ambiguo.getTotalElements()).isEqualTo(3);
        assertThat(ambiguo.getContent()).hasSize(2);
        assertThat(queries.identificarViajes(porConductor)).extracting(AgentQueryService.ViajeContribucion::id)
                .containsExactly(literal.getId());
        assertThat(queries.identificarViajes(porTexto)).extracting(AgentQueryService.ViajeContribucion::id)
                .containsExactly(literal.getId());
    }

    @Test void callbackTransaccionalDevuelveLaAclaracionYElCalculoDefinitivoSinEscrituras() {
        String origen = "Surco prueba del callback";
        var ruta = route(user(Rol.CONDUCTOR), origen, EstadoRuta.ACTIVA);
        var fecha = LocalDate.of(2026, 11, 25);
        var hora = LocalTime.of(8, 0);
        var viaje = trip(ruta, fecha, hora);
        when(current.usuarioActual()).thenReturn(user(Rol.PASAJERO));
        var mapper = new ObjectMapper();
        assertThat(AopUtils.isAopProxy(contributionTool)).isTrue();
        var callback = ToolCallbacks.from(contributionTool)[0];
        assertThat(callback.getToolDefinition().name()).isEqualTo(ContribucionAgentTools.NOMBRE);
        assertThat(mapper.readTree(callback.getToolDefinition().inputSchema()).path("properties").isEmpty()).isTrue();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var sinCantidad = new ContribucionFiltros(origen, "UPC", null, fecha, hora, null, null, false);
            var falta = mapper.readTree(callback.call("{}", new ToolContext(Map.of(
                    ContribucionAgentTools.CONTEXTO_FILTROS, sinCantidad))));
            assertThat(falta.path("estado").asText()).isEqualTo("FALTAN_PASAJEROS");
            assertThat(status.isRollbackOnly()).isFalse();

            var conCantidad = new ContribucionFiltros(origen, "UPC", null, fecha, hora, 2, null, false);
            var resultado = mapper.readTree(callback.call("{}", new ToolContext(Map.of(
                    ContribucionAgentTools.CONTEXTO_FILTROS, conCantidad))));
            assertThat(resultado.path("estado").asText()).isEqualTo("SUGERIDA");
            assertThat(resultado.path("sugerencia").path("viajeId").asLong()).isEqualTo(viaje.getId());
            assertThat(resultado.path("sugerencia").path("aporteEstimado").asDouble()).isEqualTo(6.0);
            assertThat(resultado.path("sugerencia").path("litrosGasolinaAprox").asDouble()).isEqualTo(1.11);
        });

        em.flush();
        em.clear();
        assertThat(trips.findById(viaje.getId()).orElseThrow().getPasajeros()).isZero();
        assertThat(applications.existsByViajeId(viaje.getId())).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void herramientaAbreSuTransaccionDeLecturaSinTransaccionExterna() {
        when(current.usuarioActual()).thenAnswer(invocacion -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            return Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build();
        });
        var filtros = new ContribucionFiltros(UUID.randomUUID().toString(), "UPC", null, null, null, 1, null, false);
        var contexto = new ToolContext(Map.of(ContribucionAgentTools.CONTEXTO_FILTROS, filtros));

        assertThat(contributionTool.sugerirContribucionViaje(contexto).estado()).isEqualTo(Estado.NO_ENCONTRADO);
    }

    @Test void consultasNoSincronizanEstadoNiPersistenActualizaciones() {
        Usuario owner = user(Rol.CONDUCTOR);
        Ruta persisted = route(owner, "Surco", EstadoRuta.VENCIDA);
        trip(persisted, LocalDate.of(2026, 12, 2), LocalTime.NOON);
        em.flush();
        em.clear();
        when(current.usuarioActual()).thenReturn(owner);

        assertThat(queries.rutas(0).items()).extracting(RutaResumen::estado).containsExactly(EstadoRuta.VENCIDA);
        assertThat(queries.viajes(null, 0).items()).hasSize(1);
        queries.identificarRutas("Surco", null);
        em.flush();
        em.clear();
        assertThat(routes.findById(persisted.getId()).orElseThrow().getEstado()).isEqualTo(EstadoRuta.VENCIDA);
    }

    @Test void metodosSePaginanYSeAislanPorUsuario() {
        Usuario owner = user(Rol.PASAJERO);
        Usuario other = user(Rol.CONDUCTOR);
        for (int i = 0; i < 23; i++) methods.save(MetodoCompensacion.builder().usuario(owner)
                .tipo(TipoCompensacion.FAVOR).descripcion("Apuntes " + i).activo(true).build());
        methods.save(MetodoCompensacion.builder().usuario(other).tipo(TipoCompensacion.DINERO)
                .descripcion("Privado ajeno").activo(true).build());
        when(current.usuarioActual()).thenReturn(owner);

        assertThat(queries.metodos(0).total()).isEqualTo(23);
        assertThat(queries.metodos(0).hayMas()).isTrue();
        assertThat(queries.metodos(2).items()).hasSize(3)
                .allSatisfy(m -> assertThat(m.descripcion()).doesNotContain("Privado"));
        assertThat(queries.metodos(2).hayMas()).isFalse();
    }

    @Test void viajesPropiosSeOrdenanYPaginanEnSqlSinCargarTodosLosViajes() {
        Usuario owner = user(Rol.CONDUCTOR);
        Ruta route = route(owner, "Surco", EstadoRuta.ACTIVA);
        for (int i = 0; i < 25; i++) trip(route, LocalDate.of(2026, 12, 1).plusDays(i), LocalTime.NOON);
        trip(route(user(Rol.CONDUCTOR), "Ajena", EstadoRuta.ACTIVA), LocalDate.of(2027, 1, 1), LocalTime.NOON);
        when(current.usuarioActual()).thenReturn(owner);

        var first = queries.viajes(EstadoViaje.PROGRAMADO, 0);
        assertThat(first.total()).isEqualTo(25);
        assertThat(first.items()).hasSize(10);
        assertThat(first.items().getFirst().fecha()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(first.hayMas()).isTrue();
        assertThat(queries.viajes(null, 2).items()).hasSize(5);
    }

    private Usuario user(Rol role) {
        String random = UUID.randomUUID().toString();
        Usuario usuario = users.save(Usuario.builder().correoInstitucional(random + "@upc.edu.pe")
                .telefono(random).nombre("Ana").apellidos("Prueba").contrasenaHash("hash-test")
                .rolPrincipal(role).aceptaTerminos(true).build());
        roles.asegurarPerfil(usuario, role);
        return usuario;
    }

    private Ruta route(Usuario owner, String origin, EstadoRuta state) {
        return routes.save(Ruta.builder().conductor(owner).origen(origin).destino("UPC")
                .puntosReferencia("Parque").capacidadMaxima(4).estado(state).build());
    }

    private Viaje trip(Ruta route, LocalDate date, LocalTime time) {
        return trips.save(Viaje.builder().ruta(route).fecha(date).hora(time).dia("Miercoles")
                .estado(EstadoViaje.PROGRAMADO).build());
    }

    private void application(Usuario passenger, Viaje trip, EstadoSolicitud state) {
        applications.save(Solicitud.builder().viaje(trip).pasajero(passenger).estado(state)
                .preferenciaCompensacion(TipoCompensacion.DINERO).build());
    }

    @TestConfiguration
    static class QueryConfig {
        @Bean Clock queryClock() { return Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima")); }
        @Bean RutaMapper rutaMapper() { return Mappers.getMapper(RutaMapper.class); }
        @Bean BusquedaMapper busquedaMapper() { return Mappers.getMapper(BusquedaMapper.class); }
    }
}
