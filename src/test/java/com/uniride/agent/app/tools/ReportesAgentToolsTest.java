package com.uniride.agent.app.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.uniride.agent.app.intent.HistorialPdfIntent.Filtros;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.repositories.ViajeRepository;
import com.uniride.services.EstadisticasService;
import com.uniride.services.PerfilRolService;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ReportesAgentToolsTest {
    private static final long ID = 17L;
    private static final String CORREO = "ana@upc.edu.pe";

    private final ViajeRepository viajes = mock(ViajeRepository.class);
    private final SolicitudRepository solicitudes = mock(SolicitudRepository.class);
    private final PenalidadRepository penalidades = mock(PenalidadRepository.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final UsuarioService usuarioService = new UsuarioService(
            usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
    private final EstadisticasService estadisticas = new EstadisticasService(
            viajes, solicitudes, penalidades, usuarioService);
    private final ReportesAgentTools tools = new ReportesAgentTools(estadisticas, usuarioService);

    @AfterEach
    void limpiarIdentidad() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Rol.class)
    void consultaResumenPropioConViajesCompletadosDeAmbosRoles(Rol rolActivo) {
        autenticar(rolActivo, rolActivo);
        when(viajes.countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO)).thenReturn(8L);
        when(solicitudes.contarRealizadosComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO))
                .thenReturn(4L);
        when(viajes.countByRutaConductorIdAndEstado(ID, EstadoViaje.CANCELADO)).thenReturn(2L);
        when(solicitudes.contarViajesCanceladosComoPasajero(ID, EstadoViaje.CANCELADO)).thenReturn(1L);
        when(penalidades.countByUsuarioId(ID)).thenReturn(5L);

        assertThat(tools.consultarMisEstadisticas()).isEqualTo(new EstadisticasRespuesta(8, 4, 3, 5));

        verify(usuarios).findByCorreoInstitucional(CORREO);
        verify(viajes).countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO);
        verify(viajes).countByRutaConductorIdAndEstado(ID, EstadoViaje.CANCELADO);
        verify(solicitudes).contarRealizadosComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);
        verify(solicitudes).contarViajesCanceladosComoPasajero(ID, EstadoViaje.CANCELADO);
        verify(penalidades).countByUsuarioId(ID);
        verifyNoMoreInteractions(usuarios, viajes, solicitudes, penalidades);
    }

    @Test
    void conservaCerosCuandoNoHayViajes() {
        autenticar(Rol.PASAJERO, Rol.PASAJERO);

        assertThat(tools.consultarMisEstadisticas()).isEqualTo(new EstadisticasRespuesta(0, 0, 0, 0));
    }

    @ParameterizedTest
    @EnumSource(Rol.class)
    void devuelveSieteDiasDeFrecuenciaHistoricaConCincoViajesCompletados(Rol rolActivo) {
        autenticar(rolActivo, rolActivo);
        when(viajes.countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO)).thenReturn(3L);
        when(solicitudes.contarRealizadosComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO))
                .thenReturn(2L);
        when(viajes.frecuenciaPorDiaComoConductor(ID, EstadoViaje.COMPLETADO))
                .thenReturn(List.<Object[]>of(new Object[]{"Lunes", 2L}, new Object[]{"Domingo", 1L}));
        when(solicitudes.frecuenciaPorDiaComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO))
                .thenReturn(List.<Object[]>of(new Object[]{"Lunes", 1L}, new Object[]{"Viernes", 1L}));
        when(viajes.countByEstadoAndCanceladoPorId(EstadoViaje.CANCELADO, ID)).thenReturn(1L);
        when(viajes.contarCanceladosPorTerceros(ID, EstadoViaje.CANCELADO)).thenReturn(2L);

        var respuesta = tools.consultarMiFrecuenciaViajes();

        assertThat(respuesta.suficientesDatos()).isTrue();
        assertThat(respuesta.mensaje()).isNull();
        assertThat(respuesta.viajesPorDia()).containsExactly(
                new DiaCantidadRespuesta("Lunes", 3),
                new DiaCantidadRespuesta("Martes", 0),
                new DiaCantidadRespuesta("Miercoles", 0),
                new DiaCantidadRespuesta("Jueves", 0),
                new DiaCantidadRespuesta("Viernes", 1),
                new DiaCantidadRespuesta("Sabado", 0),
                new DiaCantidadRespuesta("Domingo", 1));
        assertThat(respuesta.canceladosPorMi()).isEqualTo(1);
        assertThat(respuesta.canceladosPorTerceros()).isEqualTo(2);
        verify(viajes).frecuenciaPorDiaComoConductor(ID, EstadoViaje.COMPLETADO);
        verify(solicitudes).frecuenciaPorDiaComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);
        verifyNoInteractions(penalidades);
    }

    @Test
    void conservaMensajeYCancelacionesCuandoLaFrecuenciaTieneDatosInsuficientes() {
        autenticar(Rol.PASAJERO, Rol.PASAJERO);
        when(solicitudes.contarRealizadosComoPasajero(ID, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO))
                .thenReturn(4L);
        when(viajes.countByEstadoAndCanceladoPorId(EstadoViaje.CANCELADO, ID)).thenReturn(2L);
        when(viajes.contarCanceladosPorTerceros(ID, EstadoViaje.CANCELADO)).thenReturn(3L);

        var respuesta = tools.consultarMiFrecuenciaViajes();

        assertThat(respuesta.suficientesDatos()).isFalse();
        assertThat(respuesta.mensaje()).isEqualTo(
                "Tienes 4 viaje(s) completado(s); necesitas al menos 5 para generar el análisis semanal.");
        assertThat(respuesta.viajesPorDia()).isEmpty();
        assertThat(respuesta.canceladosPorMi()).isEqualTo(2);
        assertThat(respuesta.canceladosPorTerceros()).isEqualTo(3);
    }

    @Test
    void limitaElRankingYLasPenalidadesPorRutaAlConductorAutenticado() {
        autenticar(Rol.CONDUCTOR, Rol.CONDUCTOR);
        when(viajes.countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO)).thenReturn(7L);
        when(viajes.rankingRutasComoConductor(ID, EstadoViaje.COMPLETADO))
                .thenReturn(List.<Object[]>of(new Object[]{"Surco", "UPC", 5L}, new Object[]{"Barranco", "UPC", 2L}));
        when(viajes.penalidadesPorRutaComoConductor(ID))
                .thenReturn(List.<Object[]>of(new Object[]{"Barranco", "UPC", 3L}));

        var respuesta = tools.consultarRankingMisRutas();

        assertThat(respuesta.suficientesDatos()).isTrue();
        assertThat(respuesta.mensaje()).isNull();
        assertThat(respuesta.ranking()).containsExactly(
                new RutaFrecuenteRespuesta("Surco", "UPC", 5, 0),
                new RutaFrecuenteRespuesta("Barranco", "UPC", 2, 3));
        verify(viajes).countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO);
        verify(viajes).rankingRutasComoConductor(ID, EstadoViaje.COMPLETADO);
        verify(viajes).penalidadesPorRutaComoConductor(ID);
        verifyNoMoreInteractions(viajes);
        verifyNoInteractions(solicitudes, penalidades);
    }

    @Test
    void rechazaRankingConRolActivoPasajeroAunqueLaAutoridadAnteriorSeaConductor() {
        autenticar(Rol.PASAJERO, Rol.CONDUCTOR);

        assertThatThrownBy(tools::consultarRankingMisRutas).isInstanceOf(NoPermitidoException.class);
        verifyNoInteractions(viajes, solicitudes, penalidades);
    }

    @Test
    void rankingRequiereCincoViajesCompletadosComoConductor() {
        autenticar(Rol.CONDUCTOR, Rol.CONDUCTOR);
        when(viajes.countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO)).thenReturn(4L);

        var respuesta = tools.consultarRankingMisRutas();

        assertThat(respuesta.suficientesDatos()).isFalse();
        assertThat(respuesta.mensaje()).isEqualTo("Tienes 4 viaje(s) completado(s) como conductor; "
                + "necesitas al menos 5 para generar el ranking de rutas.");
        assertThat(respuesta.ranking()).isEmpty();
        verify(viajes).countByRutaConductorIdAndEstado(ID, EstadoViaje.COMPLETADO);
        verifyNoMoreInteractions(viajes);
        verifyNoInteractions(solicitudes, penalidades);
    }

    @Test
    void herramientasRequierenIdentidadAutenticada() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(tools::consultarMisEstadisticas).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(tools::consultarMiFrecuenciaViajes).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(tools::consultarRankingMisRutas).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(() -> tools.prepararDescargaHistorialPdf(contextoPdf(null, null, "todos")))
                .isInstanceOf(NoAutorizadoException.class);
        verifyNoInteractions(usuarios, viajes, solicitudes, penalidades);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            | | | /api/historial/pdf?tipo=todos | todos
            | | CoNdUcToR | /api/historial/pdf?tipo=conductor | conductor
            | | pasajero | /api/historial/pdf?tipo=pasajero | pasajero
            2026-10-01 | | todos | /api/historial/pdf?desde=2026-10-01&tipo=todos | todos
            | 2026-10-31 | todos | /api/historial/pdf?hasta=2026-10-31&tipo=todos | todos
            2026-10-01 | 2026-10-31 | conductor | /api/historial/pdf?desde=2026-10-01&hasta=2026-10-31&tipo=conductor | conductor
            """)
    void preparaDescriptorSinConsultarNiGenerarHistorial(String desde, String hasta, String tipo,
                                                        String url, String tipoEsperado) {
        autenticar(Rol.PASAJERO, Rol.PASAJERO);

        var descriptor = tools.prepararDescargaHistorialPdf(contextoPdf(
                desde == null ? null : LocalDate.parse(desde), hasta == null ? null : LocalDate.parse(hasta), tipo));

        assertThat(descriptor.nombreArchivo()).isEqualTo("historial-viajes.pdf");
        assertThat(descriptor.urlDescarga()).isEqualTo(url);
        assertThat(descriptor.desde()).isEqualTo(desde);
        assertThat(descriptor.hasta()).isEqualTo(hasta);
        assertThat(descriptor.tipo()).isEqualTo(tipoEsperado);
        assertThat(descriptor.mensaje()).contains("Preparé la solicitud", "se generará", "descarga autenticada");
        verify(usuarios).findByCorreoInstitucional(CORREO);
        verifyNoMoreInteractions(usuarios);
        verifyNoInteractions(viajes, solicitudes, penalidades);
    }

    @Test
    void rechazaRangoInvertidoTipoInvalidoYContextoSinFiltros() {
        autenticar(Rol.CONDUCTOR, Rol.CONDUCTOR);

        assertThatThrownBy(() -> tools.prepararDescargaHistorialPdf(
                contextoPdf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31), "todos")))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("posterior");
        assertThatThrownBy(() -> tools.prepararDescargaHistorialPdf(contextoPdf(null, null, "conductor&usuarioId=999")))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("tipo");
        assertThatThrownBy(() -> tools.prepararDescargaHistorialPdf(new ToolContext(Map.of())))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("período");
        verifyNoInteractions(viajes, solicitudes, penalidades);
    }

    private ToolContext contextoPdf(LocalDate desde, LocalDate hasta, String tipo) {
        return new ToolContext(Map.of(ReportesAgentTools.CONTEXTO_FILTROS_PDF, new Filtros(desde, hasta, tipo)));
    }

    private void autenticar(Rol rolActivo, Rol autoridad) {
        when(usuarios.findByCorreoInstitucional(CORREO)).thenReturn(Optional.of(
                Usuario.builder().id(ID).correoInstitucional(CORREO).rolPrincipal(rolActivo).build()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                CORREO, null, List.of(new SimpleGrantedAuthority("ROLE_" + autoridad.name()))));
        SecurityContextHolder.setContext(context);
    }
}
