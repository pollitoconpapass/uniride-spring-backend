package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.responses.SolicitudesHistorialResumenRespuesta;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

@ExtendWith(MockitoExtension.class)
class MisSolicitudesUs13ServiceTest {
    @Mock SolicitudRepository solicitudRepository;
    @Mock ViajeRepository viajeRepository;
    @Mock PerfilRepository perfilRepository;
    @Mock AcuerdoCompensacionRepository acuerdoRepository;
    @Mock MetodoCompensacionRepository metodoRepository;
    @Mock SolicitudMapper solicitudMapper;
    @Mock UsuarioService usuarioService;
    @Mock NotificacionService notificacionService;
    @Mock PenalidadRepository penalidadRepository;
    SolicitudService service;
    Usuario pasajero;

    @BeforeEach
    void preparar() {
        service = new SolicitudService(solicitudRepository, viajeRepository, perfilRepository,
                acuerdoRepository, metodoRepository, solicitudMapper, usuarioService,
                notificacionService, penalidadRepository);
        pasajero = Usuario.builder().id(22L).build();
    }

    private Solicitud solicitud(Long id, Long viajeId, EstadoSolicitud estado) {
        Viaje viaje = Viaje.builder().id(viajeId).estado(EstadoViaje.CANCELADO)
                .fecha(LocalDate.now().minusDays(1)).hora(LocalTime.of(9, 0)).build();
        return Solicitud.builder().id(id).viaje(viaje).pasajero(pasajero)
                .estado(estado).build();
    }

    @Test
    void historialSinSolicitudesNoConsultaPenalidades() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        var r = service.historialMisSolicitudes(0, 10);
        assertThat(r.solicitudes().getContent()).isEmpty();
        assertThat(r.mensaje()).contains("historial");
        verify(penalidadRepository, never()).findByUsuarioIdAndViajeIdIn(anyLong(), any());
    }

    @Test
    void historialEnviaIdentidadDelPasajeroYPagina() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(),
                eq(PageRequest.of(2, 5))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));
        service.historialMisSolicitudes(2, 5);
        verify(solicitudRepository).buscarHistorialUS13(eq(22L), any(), any(), any(), any(),
                eq(PageRequest.of(2, 5)));
    }

    @Test
    void historialIncluyePenalidadesPropiasYSoloLectura() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        Solicitud s = solicitud(10L, 30L, EstadoSolicitud.ACEPTADA);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 10), 1));
        Penalidad p = Penalidad.builder().id(40L).usuario(pasajero).viaje(s.getViaje())
                .tipo(TipoPenalidad.GRAVE).motivo("Cancelacion tardia").build();
        when(penalidadRepository.findByUsuarioIdAndViajeIdIn(eq(22L), eq(List.of(30L))))
                .thenReturn(List.of(p));
        var r = service.historialMisSolicitudes(0, 10);
        assertThat(r.solicitudes().getContent()).hasSize(1);
        assertThat(r.solicitudes().getContent().get(0).soloLectura()).isTrue();
        assertThat(r.solicitudes().getContent().get(0).penalidades()).hasSize(1);
        assertThat(r.solicitudes().getContent().get(0).penalidades().get(0).tipo())
                .isEqualTo(TipoPenalidad.GRAVE);
        assertThat(r.solicitudes().getContent().get(0).estadoViaje()).isEqualTo(EstadoViaje.CANCELADO);
        assertThat(r.mensaje()).isNull();
        verify(solicitudRepository, never()).save(any());
    }

    @Test
    void historialSinPenalidadesDevuelveListaVacia() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        Solicitud s = solicitud(10L, 30L, EstadoSolicitud.CANCELADA);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 10), 1));
        var r = service.historialMisSolicitudes(0, 10);
        assertThat(r.solicitudes().getContent().get(0).penalidades()).isEmpty();
    }

    @Test
    void penalidadesSeCarganPorLoteYPasajeroNoPorTodosLosUsuarios() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        Solicitud s1 = solicitud(10L, 30L, EstadoSolicitud.CANCELADA);
        Solicitud s2 = solicitud(11L, 31L, EstadoSolicitud.RECHAZADA);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s1, s2), PageRequest.of(0, 10), 2));
        when(penalidadRepository.findByUsuarioIdAndViajeIdIn(eq(22L), eq(List.of(30L, 31L))))
                .thenReturn(List.of(Penalidad.builder().id(1L).usuario(pasajero)
                        .viaje(s1.getViaje()).tipo(TipoPenalidad.LEVE).motivo("X").build()));
        var r = service.historialMisSolicitudes(0, 10);
        assertThat(r.solicitudes().getContent().get(0).penalidades()).hasSize(1);
        assertThat(r.solicitudes().getContent().get(1).penalidades()).isEmpty();
        verify(penalidadRepository, times(1)).findByUsuarioIdAndViajeIdIn(eq(22L), any());
    }

    @Test
    void paginacionInvalidaDelHistorialEs400() {
        assertThatThrownBy(() -> service.historialMisSolicitudes(-1, 10))
                .isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> service.historialMisSolicitudes(0, 0))
                .isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> service.historialMisSolicitudes(0, 101))
                .isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(usuarioService);
    }

    @Test
    void paginacionInvalidaDelListadoEs400() {
        assertThatThrownBy(() -> service.listarMisSolicitudes(null, -1, 10))
                .isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(usuarioService);
    }

    @Test
    void listadoOriginalMantieneFiltroYEstado() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(solicitudRepository.buscarPorPasajeroYEstado(eq(22L), eq(EstadoSolicitud.PENDIENTE), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        var r = service.listarMisSolicitudes(EstadoSolicitud.PENDIENTE, 0, 10);
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void historialMantieneMensajeDePaginaVaciaFueraDeRango() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(solicitudRepository.buscarHistorialUS13(eq(22L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 10), 15));
        SolicitudesHistorialResumenRespuesta r=service.historialMisSolicitudes(9, 10);
        assertThat(r.mensaje()).contains("pagina anterior");
    }
}
