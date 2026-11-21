package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.dto.responses.*;
import com.uniride.entities.Usuario;
import com.uniride.enums.*;
import com.uniride.services.UsuarioService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ViajeAgentToolsTest {
    @Mock AgentQueryService consultas;
    @Mock ConfirmationService confirmaciones;
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
    void buscarViajesDelegaNormalizacionYDevuelveResumenes() {
        var trip = new ViajeResumen(7L, 1L, "Surco", "UPC", "Miercoles", LocalDate.of(2026, 12, 1),
                LocalTime.of(7, 0), "Parque", "Ana", 4, EstadoViaje.PROGRAMADO, null);
        when(consultas.buscar(null, null, "miércoles", null, null, null))
                .thenReturn(new PaginaAgente<>(List.of(trip), 0, 10, 1, false));
        assertThat(tools.buscarViajes(null, null, "miércoles", null)).containsExactly(trip);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void solicitarUnirseRechazaAlConductorSinConsultarViajes() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        assertThat(tools.solicitarUnirse(null, null, null, null, null, TipoCompensacion.FAVOR).estado())
                .isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void solicitarUnirseExigePreferenciaDeCompensacion() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        assertThat(tools.solicitarUnirse(null, null, null, null, null, null).estado())
                .isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void solicitarUnirseNoRegistraNadaSiHayVariosViajes() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(consultas.identificarViajes(null, null, null, null, null))
                .thenReturn(new PageImpl<>(List.of(viaje(7L), viaje(8L)), PageRequest.of(0, 2), 125));
        assertThat(tools.solicitarUnirse(null, null, null, null, null, TipoCompensacion.DINERO).estado())
                .isEqualTo(EstadoOperacion.AMBIGUO);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void solicitarUnirsePreparaSolicitudSinEnviarlaAlConductor() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        LocalTime hora = LocalTime.of(7, 0);
        LocalDate fecha = LocalDate.of(2026, 12, 1);
        when(consultas.identificarViajes("Surco", "UPC", "Lunes", fecha, hora))
                .thenReturn(new PageImpl<>(List.of(viaje(7L)), PageRequest.of(0, 2), 1));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el cupo.");
        when(confirmaciones.prepararSolicitud(eq(7L), any())).thenReturn(pendiente);

        assertThat(tools.solicitarUnirse("Surco", "UPC", "Lunes", fecha, hora, TipoCompensacion.GASOLINA))
                .isSameAs(pendiente);
        var captor = ArgumentCaptor.forClass(SolicitudRequest.class);
        verify(confirmaciones).prepararSolicitud(eq(7L), captor.capture());
        assertThat(captor.getValue().preferenciaCompensacion()).isEqualTo(TipoCompensacion.GASOLINA);
        assertThat(captor.getValue().metodoCompensacionId()).isNull();
    }

    @Test
    void crearViajeNoCreaNadaSiLaRutaEsAmbigua() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(consultas.identificarRutas("Surco", null)).thenReturn(
                new PageImpl<>(List.of(ruta(1L), ruta(101L)), PageRequest.of(0, 2), 2));
        assertThat(tools.crearViaje("Surco", null, LocalDate.now().plusDays(2), LocalTime.of(7, 0)).estado())
                .isEqualTo(EstadoOperacion.AMBIGUO);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void crearViajePreparaLaRutaUnicaEncontrada() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(consultas.identificarRutas("Surco", null)).thenReturn(new PageImpl<>(List.of(ruta(1L))));
        LocalDate fecha = LocalDate.now().plusDays(2);
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el viaje.");
        when(confirmaciones.prepararViaje(eq(1L), any())).thenReturn(pendiente);
        assertThat(tools.crearViaje("Surco", null, fecha, LocalTime.of(7, 0))).isSameAs(pendiente);
        verify(confirmaciones).prepararViaje(1L, new ViajeRequest(fecha, LocalTime.of(7, 0)));
    }

    @Test
    void pasajeroNoPuedeCrearViaje() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        assertThat(tools.crearViaje("Surco", null, LocalDate.now().plusDays(2), LocalTime.NOON).estado())
                .isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void fechaFaltanteNoPreparaNiConsulta() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        assertThat(tools.crearViaje("Surco", null, null, null).estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void paginasAdicionalesSeTransmitenSinDescartarMetadatos() {
        var page = new PaginaAgente<ViajeResumen>(List.of(), 2, 10, 20, false);
        when(consultas.viajes(EstadoViaje.PROGRAMADO, 2)).thenReturn(page);
        assertThat(tools.consultarMisViajes(EstadoViaje.PROGRAMADO, 2)).isSameAs(page);
        verifyNoInteractions(confirmaciones);
    }

    private BusquedaRespuesta viaje(Long id) {
        return new BusquedaRespuesta(id, LocalDate.of(2026, 12, 1), LocalTime.of(7, 0), "Lunes", 1L,
                "Surco", "UPC", "Parque", null, null, 4, 10L, "Juan", null);
    }

    private RutaRespuesta ruta(Long id) {
        return new RutaRespuesta(id, "Surco", "UPC", "Parque", null, null, 4,
                EstadoRuta.ACTIVA, LocalDateTime.now(), 10L, "Juan");
    }
}
