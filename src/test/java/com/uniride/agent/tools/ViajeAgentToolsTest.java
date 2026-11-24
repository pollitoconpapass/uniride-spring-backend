package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.config.TiempoConfig;
import com.uniride.dto.requests.*;
import com.uniride.dto.responses.*;
import com.uniride.entities.Usuario;
import com.uniride.enums.*;
import com.uniride.exceptions.CamposInvalidosException;
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
    private ViajeAgentTools tools;
    private Usuario pasajero;
    private Usuario conductor;

    @BeforeEach
    void preparar() {
        pasajero = Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build();
        conductor = Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build();
        tools = new ViajeAgentTools(consultas, confirmaciones, usuarioService,
                Clock.fixed(Instant.parse("2026-11-23T15:00:00Z"), TiempoConfig.LIMA));
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
        var resultado = tools.solicitarUnirse("Surco", "UPC", null, null, null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(resultado.aclaracion().campos()).singleElement().satisfies(campo -> {
            assertThat(campo.id()).isEqualTo("compensacion");
            assertThat(campo.opciones()).extracting(OpcionAclaracion::valor).containsExactly("DINERO", "GASOLINA", "FAVOR");
        });
        assertThat(resultado.aclaracion().opcionesRapidas()).isTrue();
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
        var faltaFecha = tools.crearViaje("Surco", null, null, LocalTime.of(8, 30));
        var faltaHora = tools.crearViaje("Surco", null, LocalDate.of(2026, 12, 1), null);

        assertThat(faltaFecha.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(faltaFecha.aclaracion().campos()).extracting(CampoAclaracion::id).containsExactly("fecha");
        assertThat(faltaHora.aclaracion().campos()).extracting(CampoAclaracion::id).containsExactly("hora");
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void paginasAdicionalesSeTransmitenSinDescartarMetadatos() {
        var page = new PaginaAgente<ViajeResumen>(List.of(), 2, 10, 20, false);
        when(consultas.viajes(EstadoViaje.PROGRAMADO, 2)).thenReturn(page);
        assertThat(tools.consultarMisViajes(EstadoViaje.PROGRAMADO, 2)).isSameAs(page);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void reprogramarViajeRechazaAlPasajero() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        assertThat(tools.reprogramarViaje("Surco", null, null, null, null,
                LocalDate.now().plusDays(2), LocalTime.of(9, 0)).estado()).isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void reprogramarViajeExigeLaNuevaSalida() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        var faltaNuevaHora = tools.reprogramarViaje("Surco", null, null, null, null,
                LocalDate.of(2026, 12, 1), null);

        assertThat(faltaNuevaHora.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(faltaNuevaHora.aclaracion().campos()).extracting(CampoAclaracion::id).containsExactly("nuevaHora");
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void reprogramarUnaFechaPasadaPideCorregirSoloLaFechaNueva() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(consultas.identificarViajesConductor("Surco", null, null, null, null))
                .thenReturn(new PageImpl<>(List.of(resumen(7L))));
        LocalDate ayer = LocalDate.of(2026, 11, 22);
        LocalTime hora = LocalTime.of(9, 0);
        when(confirmaciones.prepararReprogramacionViaje(7L, ayer, hora))
                .thenThrow(new CamposInvalidosException("La nueva fecha y hora deben ser futuras"));

        var resultado = tools.reprogramarViaje("Surco", null, null, null, null, ayer, hora);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(resultado.aclaracion().campos()).extracting(CampoAclaracion::id).containsExactly("nuevaFecha");
        assertThat(resultado.confirmacion()).isNull();
    }

    @Test
    void reprogramarViajeNoPreparaSiHayVariasCoincidencias() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(consultas.identificarViajesConductor("Surco", null, null, null, null)).thenReturn(
                new PageImpl<>(List.of(resumen(7L), resumen(8L)), PageRequest.of(0, 2), 2));
        assertThat(tools.reprogramarViaje("Surco", null, null, null, null,
                LocalDate.now().plusDays(2), LocalTime.of(9, 0)).estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void reprogramarViajePreparaLaCoincidenciaUnica() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(consultas.identificarViajesConductor("Surco", null, null, null, null))
                .thenReturn(new PageImpl<>(List.of(resumen(7L))));
        LocalDate nueva = LocalDate.now().plusDays(2);
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el cambio.");
        when(confirmaciones.prepararReprogramacionViaje(7L, nueva, LocalTime.of(9, 0))).thenReturn(pendiente);

        assertThat(tools.reprogramarViaje("Surco", null, null, null, null, nueva, LocalTime.of(9, 0)))
                .isSameAs(pendiente);
    }

    @Test
    void cancelarSolicitudNoPreparaSiHayVariasYSoloSePidioUna() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(consultas.candidatasCancelacion(null, null, null, null, null))
                .thenReturn(List.of(solicitud(1L), solicitud(2L)));
        assertThat(tools.cancelarSolicitud(null, null, null, null, null, false).estado())
                .isEqualTo(EstadoOperacion.AMBIGUO);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void cancelarSolicitudOfreceSoloLasHorasAutorizadasParaDistinguir() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        var solicitud = new SolicitudResumen(2L, 102L, "Surco", "UPC", "Lunes", LocalDate.of(2026, 12, 1),
                LocalTime.of(9, 0), EstadoSolicitud.PENDIENTE);
        when(consultas.candidatasCancelacion(null, null, null, null, null))
                .thenReturn(List.of(solicitud(1L), solicitud));

        var resultado = tools.cancelarSolicitud(null, null, null, null, null, false);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        assertThat(resultado.aclaracion().campos()).singleElement().satisfies(campo -> {
            assertThat(campo.id()).isEqualTo("hora");
            assertThat(campo.opciones()).extracting(OpcionAclaracion::valor).containsExactly("07:00", "09:00");
        });
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void cancelarSolicitudPreparaTodasLasPendientesCuandoSePide() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        var candidatas = List.of(solicitud(1L), solicitud(2L));
        when(consultas.candidatasCancelacion(null, null, null, null, null)).thenReturn(candidatas);
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma la cancelación.");
        when(confirmaciones.prepararCancelacionSolicitudes(candidatas)).thenReturn(pendiente);

        assertThat(tools.cancelarSolicitud(null, null, null, null, null, true)).isSameAs(pendiente);
    }

    @Test
    void cancelarSolicitudRechazaAlConductor() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        assertThat(tools.cancelarSolicitud(null, null, null, null, null, true).estado())
                .isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    private ViajeResumen resumen(Long id) {
        return new ViajeResumen(id, 1L, "Surco", "UPC", "Lunes", LocalDate.of(2026, 12, 1),
                LocalTime.of(7, 0), "Parque", "Ana", 4, EstadoViaje.PROGRAMADO, null);
    }

    private SolicitudResumen solicitud(Long id) {
        return new SolicitudResumen(id, id + 100, "Surco", "UPC", "Lunes", LocalDate.of(2026, 12, 1),
                LocalTime.of(7, 0), EstadoSolicitud.PENDIENTE);
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
