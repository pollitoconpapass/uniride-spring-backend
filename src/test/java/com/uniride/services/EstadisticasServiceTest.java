package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.BusinessException;
import com.uniride.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

class EstadisticasServiceTest {
    ViajeRepository viajes = mock(ViajeRepository.class);
    SolicitudRepository solicitudes = mock(SolicitudRepository.class);
    PenalidadRepository penalidades = mock(PenalidadRepository.class);
    UsuarioService usuarios = mock(UsuarioService.class);
    EstadisticasService servicio = new EstadisticasService(viajes, solicitudes, penalidades, usuarios);
    LocalDate hoy = LocalDate.of(2026, 11, 5);
    Usuario usuario = Usuario.builder().id(1L).nombre("Ana").apellidos("Perez").build();
    Ruta ruta = Ruta.builder().conductor(usuario).origen("Surco").destino("UPC").build();

    @BeforeEach void preparar() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        ReflectionTestUtils.setField(servicio, "clock", Clock.fixed(
                hoy.atTime(10, 0).atZone(com.uniride.config.TiempoConfig.LIMA).toInstant(),
                com.uniride.config.TiempoConfig.LIMA));
    }

    @Test void cancelacionesUsanAutorInclusoSinPenalidadYSinMinimoDeViajes() {
        when(viajes.countByEstadoAndCanceladoPorId(EstadoViaje.CANCELADO, 1L)).thenReturn(2L);
        when(viajes.contarCanceladosPorTerceros(1L, EstadoViaje.CANCELADO)).thenReturn(3L);
        var respuesta = servicio.analisisSemanal();
        assertThat(respuesta.canceladosPorMi()).isEqualTo(2);
        assertThat(respuesta.canceladosPorTerceros()).isEqualTo(3);
        assertThat(respuesta.suficientesDatos()).isFalse();
        verifyNoInteractions(penalidades);
    }

    @Test void frecuenciaHistoricaCombinaAmbosRolesYCompletaDiasEnCero() {
        when(viajes.countByRutaConductorIdAndEstado(1L, EstadoViaje.COMPLETADO)).thenReturn(5L);
        when(viajes.frecuenciaPorDiaComoConductor(1L, EstadoViaje.COMPLETADO))
                .thenReturn(Collections.singletonList(new Object[]{"Lunes", 4L}));
        when(solicitudes.frecuenciaPorDiaComoPasajero(1L, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO))
                .thenReturn(Collections.singletonList(new Object[]{"Lunes", 1L}));
        var respuesta = servicio.analisisSemanal();
        assertThat(respuesta.suficientesDatos()).isTrue();
        assertThat(respuesta.viajesPorDia()).hasSize(7);
        assertThat(respuesta.viajesPorDia().getFirst().cantidad()).isEqualTo(5);
        assertThat(respuesta.viajesPorDia().getLast().cantidad()).isZero();
    }

    Viaje viaje(long id, int hora, EstadoViaje estado) {
        return Viaje.builder().id(id).ruta(ruta).fecha(hoy).hora(LocalTime.of(hora, 0))
                .dia("Jueves").estado(estado).build();
    }

    @Test void pdfExcluyeProgramadoFuturoDeHoyEnAmbosRoles() {
        Viaje futuro = viaje(2L, 11, EstadoViaje.PROGRAMADO);
        when(viajes.historialComoConductor(1L, EstadoViaje.PROGRAMADO, hoy)).thenReturn(List.of(futuro));
        when(solicitudes.historialComoPasajero(eq(1L), any())).thenReturn(List.of(
                Solicitud.builder().viaje(futuro).estado(EstadoSolicitud.ACEPTADA).build()));
        assertThatThrownBy(() -> servicio.exportarHistorialPDF(null, null, "todos"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No hay historial");
    }

    @Test void pdfIncluyeSalidaExactaYCanceladoFuturoYRespetaFiltros() throws Exception {
        when(viajes.historialComoConductor(1L, EstadoViaje.PROGRAMADO, hoy)).thenReturn(List.of(
                viaje(2L, 10, EstadoViaje.PROGRAMADO), viaje(3L, 12, EstadoViaje.CANCELADO)));
        byte[] pdf = servicio.exportarHistorialPDF(hoy, hoy, "conductor");
        try (var doc = Loader.loadPDF(pdf)) {
            String texto = new PDFTextStripper().getText(doc);
            assertThat(texto).contains("Ana Perez", "PROGRAMADO", "CANCELADO", "Surco - UPC");
        }
        verifyNoInteractions(solicitudes);
    }
}
