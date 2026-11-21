package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.mappers.*;
import com.uniride.repositories.*;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest
@Import({AgentQueryService.class, AgentQueryPersistenceTest.QueryConfig.class})
class AgentQueryPersistenceTest {
    @Autowired AgentQueryService queries;
    @Autowired UsuarioRepository users;
    @Autowired RutaRepository routes;
    @Autowired ViajeRepository trips;
    @Autowired MetodoCompensacionRepository methods;
    @Autowired EntityManager em;
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
        Ruta route = route(owner, "Surco", EstadoRuta.ACTIVA);
        for (int i = 0; i < 25; i++) trip(route, LocalDate.of(2026, 11, 22), LocalTime.of(8, 0));
        Viaje first = trip(route, LocalDate.of(2026, 12, 2), LocalTime.of(8, 0));
        Viaje second = trip(route, LocalDate.of(2026, 12, 2), LocalTime.of(9, 0));
        when(current.usuarioActual()).thenReturn(user(Rol.PASAJERO));

        var matches = queries.identificarViajes("Surco", "UPC", null, LocalDate.of(2026, 12, 2), null);
        assertThat(matches.getTotalElements()).isEqualTo(2);
        assertThat(matches.getContent()).extracting(v -> v.viajeId()).containsExactly(first.getId(), second.getId());
        var unique = queries.identificarViajes("Surco", "UPC", null, LocalDate.of(2026, 12, 2), LocalTime.of(9, 0));
        assertThat(unique.getTotalElements()).isEqualTo(1);
        assertThat(unique.getContent().getFirst().viajeId()).isEqualTo(second.getId());
        assertThat(queries.buscar(null, null, null, null, null, 0).hayMas()).isTrue();
        assertThat(queries.buscar(null, null, null, null, null, 2).items()).hasSize(7);
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
        return users.save(Usuario.builder().correoInstitucional(random + "@upc.edu.pe")
                .telefono(random).nombre("Ana").apellidos("Prueba").contrasenaHash("hash-test")
                .rolPrincipal(role).aceptaTerminos(true).build());
    }

    private Ruta route(Usuario owner, String origin, EstadoRuta state) {
        return routes.save(Ruta.builder().conductor(owner).origen(origin).destino("UPC")
                .puntosReferencia("Parque").capacidadMaxima(4).estado(state).build());
    }

    private Viaje trip(Ruta route, LocalDate date, LocalTime time) {
        return trips.save(Viaje.builder().ruta(route).fecha(date).hora(time).dia("Miercoles")
                .estado(EstadoViaje.PROGRAMADO).build());
    }

    @TestConfiguration
    static class QueryConfig {
        @Bean Clock queryClock() { return Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima")); }
        @Bean RutaMapper rutaMapper() { return Mappers.getMapper(RutaMapper.class); }
        @Bean BusquedaMapper busquedaMapper() { return Mappers.getMapper(BusquedaMapper.class); }
    }
}
