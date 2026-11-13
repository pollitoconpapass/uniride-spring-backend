package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.services.BusquedaService;
import com.uniride.services.RutaService;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import com.uniride.services.ViajeService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class ViajeAgentToolsTest {

    @Mock BusquedaService busquedaService;
    @Mock SolicitudService solicitudService;
    @Mock RutaService rutaService;
    @Mock ViajeService viajeService;
    @Mock UsuarioService usuarioService;
    @InjectMocks ViajeAgentTools tools;

    private Usuario pasajero;
    private Usuario conductor;

    @BeforeEach
    void preparar() {
        pasajero = Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build();
        conductor = Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build();
    }

    @Test
    void buscarViajesNormalizaElDiaYDevuelveResumenes() {
        when(busquedaService.buscar(null, null, "Miercoles", null, false, 0, 5))
                .thenReturn(new PageImpl<>(List.of(viaje(7L, "Miercoles"))));

        List<ViajeResumen> viajes = tools.buscarViajes(null, null, "miércoles", null);

        assertThat(viajes).hasSize(1);
        assertThat(viajes.get(0).viajeId()).isEqualTo(7L);
        assertThat(viajes.get(0).origen()).isEqualTo("Surco");
    }

    @Test
    void solicitarUnirseRechazaAlConductorSinConsultarViajes() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);

        OperacionResumen resultado = tools.solicitarUnirse(
                null, null, null, null, null, TipoCompensacion.FAVOR);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(busquedaService, solicitudService);
    }

    @Test
    void solicitarUnirseExigePreferenciaDeCompensacion() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);

        OperacionResumen resultado = tools.solicitarUnirse(
                null, null, null, null, null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        verifyNoInteractions(busquedaService, solicitudService);
    }

    @Test
    void solicitarUnirseNoRegistraNadaSiHayVariosViajes() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(busquedaService.buscar(null, null, null, null, false, 0, 20))
                .thenReturn(new PageImpl<>(List.of(
                        viaje(7L, "Lunes"), viaje(8L, "Martes"))));

        OperacionResumen resultado = tools.solicitarUnirse(
                null, null, null, null, null, TipoCompensacion.DINERO);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        verify(solicitudService, never()).crear(anyLong(), any());
    }

    @Test
    void solicitarUnirseEnviaLaSolicitudAlConductor() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(busquedaService.buscar("Surco", "UPC", "Lunes", LocalTime.of(7, 0),
                false, 0, 20))
                .thenReturn(new PageImpl<>(List.of(viaje(7L, "Lunes"))));
        SolicitudRespuesta respuesta = mock(SolicitudRespuesta.class);
        when(respuesta.id()).thenReturn(99L);
        when(solicitudService.crear(eq(7L), any(SolicitudRequest.class)))
                .thenReturn(respuesta);

        OperacionResumen resultado = tools.solicitarUnirse(
                "Surco", "UPC", "Lunes", null, LocalTime.of(7, 0), TipoCompensacion.GASOLINA);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(resultado.id()).isEqualTo(99L);
        ArgumentCaptor<SolicitudRequest> captor = ArgumentCaptor.forClass(SolicitudRequest.class);
        verify(solicitudService).crear(eq(7L), captor.capture());
        assertThat(captor.getValue().preferenciaCompensacion())
                .isEqualTo(TipoCompensacion.GASOLINA);
        assertThat(captor.getValue().metodoCompensacionId()).isNull();
    }

    @Test
    void crearViajeNoCreaNadaSiLaRutaEsAmbigua() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaService.listarMisRutas(null, 0, 100))
                .thenReturn(new PageImpl<>(List.of(
                        ruta(1L, "Surco"), ruta(2L, "Surco"))));

        OperacionResumen resultado = tools.crearViaje(
                "Surco", null, LocalDate.now().plusDays(2), LocalTime.of(7, 0));

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        verify(viajeService, never()).crear(anyLong(), any());
    }

    @Test
    void crearViajeUsaLaRutaUnicaEncontrada() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaService.listarMisRutas(null, 0, 100))
                .thenReturn(new PageImpl<>(List.of(ruta(1L, "Surco"))));
        LocalDate fecha = LocalDate.now().plusDays(2);
        when(viajeService.crear(eq(1L), any(ViajeRequest.class)))
                .thenReturn(new com.uniride.dto.responses.ViajeRespuesta(
                        5L, 1L, fecha, LocalTime.of(7, 0), "Lunes", false, null,
                        EstadoViaje.PROGRAMADO, 0, "Viaje creado"));

        OperacionResumen resultado = tools.crearViaje(
                "Surco", null, fecha, LocalTime.of(7, 0));

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(resultado.id()).isEqualTo(5L);
    }

    private BusquedaRespuesta viaje(Long id, String dia) {
        return new BusquedaRespuesta(id, LocalDate.now().plusDays(1), LocalTime.of(7, 0),
                dia, 1L, "Surco", "UPC", "Parque", null, null, 4, 10L, "Juan", null);
    }

    private RutaRespuesta ruta(Long id, String origen) {
        return new RutaRespuesta(id, origen, "UPC", "Parque", null, null, 4,
                EstadoRuta.ACTIVA, LocalDateTime.now(), 10L, "Juan");
    }
}
