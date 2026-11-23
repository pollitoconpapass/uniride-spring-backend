package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.dto.ContribucionFiltros;
import com.uniride.entities.Usuario;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.mappers.*;
import com.uniride.repositories.*;
import com.uniride.services.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AgentQueryServiceTest {
    @Mock RutaRepository rutas;
    @Mock ViajeRepository viajes;
    @Mock MetodoCompensacionRepository metodos;
    @Mock SolicitudRepository solicitudesRepo;
    @Mock SolicitudService solicitudes;
    @Mock UsuarioService usuarios;
    @Mock RutaMapper rutaMapper;
    @Mock BusquedaMapper busquedaMapper;
    private AgentQueryService service;

    @BeforeEach void setup() {
        service = new AgentQueryService(rutas, viajes, metodos, solicitudesRepo, solicitudes, usuarios, rutaMapper, busquedaMapper,
                Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima")));
    }

    @Test void pasajeroNoConsultaRutasNiViajesDeConductorAunqueAntesFueraConductor() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.PASAJERO).build());
        assertThatThrownBy(() -> service.rutas(0)).isInstanceOf(NoPermitidoException.class);
        assertThatThrownBy(() -> service.viajes(null, 0)).isInstanceOf(NoPermitidoException.class);
        verifyNoInteractions(rutas, viajes);
    }

    @Test void conductorNoBuscaViajesNiSolicitudesDePasajero() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.CONDUCTOR).build());
        assertThatThrownBy(() -> service.buscar(null, null, null, null, null, null)).isInstanceOf(NoPermitidoException.class);
        assertThatThrownBy(() -> service.solicitudes(null, null)).isInstanceOf(NoPermitidoException.class);
        verifyNoInteractions(viajes, solicitudes);
    }

    @Test void contribucionValidaSesionYRolAntesDeCualquierLecturaDeViajes() {
        var filtros = new ContribucionFiltros(null, null, null, null, null, null, null, false);
        when(usuarios.usuarioActual()).thenThrow(new NoAutorizadoException("Debes iniciar sesión"))
                .thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.CONDUCTOR).build())
                .thenReturn(Usuario.builder().id(8L).rolPrincipal(Rol.PASAJERO).build());

        assertThatThrownBy(() -> service.identificarViajes(filtros)).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(() -> service.identificarViajes(filtros)).isInstanceOf(NoPermitidoException.class);
        assertThat(service.identificarViajes(filtros)).isEmpty();
        verifyNoInteractions(viajes);
    }

    @Test void fechaNormalizacionYPaginaSeAplicanAntesDeLaConsultaLimitada() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.PASAJERO).build());
        LocalDate fecha = LocalDate.of(2026, 12, 2);
        when(viajes.buscarViajesRelevantesPorFecha("surco", "upc", "miercoles", LocalTime.NOON,
                null, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA, LocalDate.of(2026, 11, 21),
                LocalTime.of(10, 0), fecha, PageRequest.of(0, 2))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 2), 0));
        assertThat(service.identificarViajes(" Surco ", "UPC", "MIÉRCOLES", fecha, LocalTime.NOON)).isEmpty();
        verify(viajes).buscarViajesRelevantesPorFecha("surco", "upc", "miercoles", LocalTime.NOON,
                null, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA, LocalDate.of(2026, 11, 21),
                LocalTime.of(10, 0), fecha, PageRequest.of(0, 2));
    }

    @Test void consultasPaginadasSiempreUsanIdAutenticadoYSinSincronizacion() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(79L).rolPrincipal(Rol.CONDUCTOR).build());
        when(rutas.consultarRutasAgente(79L, EstadoRuta.ELIMINADA, null, null, PageRequest.of(3, 10)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 10), 30));
        var page = service.rutas(3);
        assertThat(page.pagina()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(30);
        assertThat(page.hayMas()).isFalse();
        verify(rutas, never()).findByConductorId(anyLong());
        verify(rutas, never()).saveAllAndFlush(any());
    }

    @Test void paginaInvalidaSeRechazaAntesDeLaConsulta() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.CONDUCTOR).build());
        assertThatThrownBy(() -> service.rutas(-1)).isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> service.viajes(null, 10001)).isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(rutas, viajes);
    }
}
