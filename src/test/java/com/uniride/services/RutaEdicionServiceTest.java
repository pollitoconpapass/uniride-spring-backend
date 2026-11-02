package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.RutaRequest;
import com.uniride.entities.Perfil;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RutaEdicionServiceTest {
    @Mock RutaRepository rutaRepository;
    @Mock RutaMapper rutaMapper;
    @Mock UsuarioService usuarioService;
    @Mock PerfilRepository perfilRepository;
    @Mock NotificacionService notificacionService;
    @Mock ViajeRepository viajeRepository;
    @Mock SolicitudRepository solicitudRepository;
    @Mock ViajeService viajeService;
    private RutaService servicio;
    private Ruta ruta;
    private Usuario conductor;
    private Usuario pasajero;

    @BeforeEach
    void preparar() {
        servicio = new RutaService(rutaRepository, rutaMapper, usuarioService, perfilRepository,
                notificacionService, viajeRepository, solicitudRepository, viajeService);
        conductor = Usuario.builder().id(10L).nombre("Ana").build();
        pasajero = Usuario.builder().id(30L).nombre("Luis").build();
        ruta = Ruta.builder().id(1L).conductor(conductor).origen("Surco")
                .destino("UPC").puntosReferencia("Puente Primavera").zonasSinParada("Ninguna")
                .capacidadMaxima(3).estado(EstadoRuta.ACTIVA).build();
        lenient().when(usuarioService.usuarioActual()).thenReturn(conductor);
        lenient().when(rutaRepository.findByIdAndConductorId(1L, 10L))
                .thenReturn(Optional.of(ruta));
    }

    private RutaRequest solicitud(int capacidad, String origen, String referencia, String nota) {
        return new RutaRequest(origen, capacidad, referencia, "Ninguna", nota);
    }

    private void prepararEdicionValida() {
        when(perfilRepository.findByUsuarioId(10L))
                .thenReturn(Optional.of(Perfil.builder().universidad("UPC").build()));
        doAnswer(inv -> {
            RutaRequest req = inv.getArgument(0);
            Ruta destino = inv.getArgument(1);
            destino.setOrigen(req.origen());
            destino.setCapacidadMaxima(req.capacidadMaxima());
            destino.setPuntosReferencia(req.puntosReferencia());
            destino.setZonasSinParada(req.zonasSinParada());
            destino.setNotaContribucion(req.notaContribucion());
            return null;
        }).when(rutaMapper).actualizarRuta(any(), eq(ruta));
        when(rutaRepository.save(ruta)).thenReturn(ruta);
    }

    private Viaje viaje(EstadoViaje estado) {
        return Viaje.builder().id(20L).ruta(ruta).estado(estado).build();
    }

    private void pasajeroAceptado() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO)));
        when(solicitudRepository.findByViajeIdAndEstadoIn(20L, List.of(EstadoSolicitud.ACEPTADA)))
                .thenReturn(List.of(Solicitud.builder().pasajero(pasajero).build()));
    }

    @Test
    void editaCapacidadSinPasajerosAceptados() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of());
        prepararEdicionValida();
        servicio.actualizar(1L, solicitud(4, "Surco", "Puente Primavera", null), false);
        assertThat(ruta.getCapacidadMaxima()).isEqualTo(4);
        verify(rutaRepository).save(ruta);
    }

    @Test
    void soloEditarNotaNoRequiereConfirmacionNiConsultaSolicitudes() {
        prepararEdicionValida();
        servicio.actualizar(1L, solicitud(3, "Surco", "Puente Primavera", "Acordar combustible"), false);
        assertThat(ruta.getNotaContribucion()).isEqualTo("Acordar combustible");
        verify(viajeRepository, never()).findByRutaId(anyLong());
    }

    @Test
    void cambioCapacidadConAceptadosExigeConfirmacion() {
        pasajeroAceptado();
        assertThatThrownBy(() -> servicio.actualizar(1L,
                solicitud(4, "Surco", "Puente Primavera", null), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("?confirmarImpacto=true");
        verify(rutaRepository, never()).save(any());
        assertThat(ruta.getCapacidadMaxima()).isEqualTo(3);
    }

    @Test
    void confirmaCambioYNotificaPasajeroAceptado() {
        pasajeroAceptado();
        prepararEdicionValida();
        servicio.actualizar(1L, solicitud(4, "Surco", "Puente Primavera", null), true);
        assertThat(ruta.getCapacidadMaxima()).isEqualTo(4);
        verify(notificacionService).notificar(eq(pasajero), eq(TipoNotificacion.ADVERTENCIA),
                eq("Ruta modificada"), anyString());
    }

    @Test
    void niConfirmandoSePuedeReducirCapacidadPorDebajoDeAceptados() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO)));
        Usuario otraPersona = Usuario.builder().id(31L).build();
        when(solicitudRepository.findByViajeIdAndEstadoIn(20L, List.of(EstadoSolicitud.ACEPTADA)))
                .thenReturn(List.of(Solicitud.builder().pasajero(pasajero).build(),
                        Solicitud.builder().pasajero(otraPersona).build()));
        assertThatThrownBy(() -> servicio.actualizar(1L,
                solicitud(1, "Surco", "Puente Primavera", null), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("capacidad");
        verify(rutaRepository, never()).save(any());
    }

    @Test
    void rutaEliminadaNoSePuedeEditar() {
        ruta.setEstado(EstadoRuta.ELIMINADA);
        assertThatThrownBy(() -> servicio.actualizar(1L,
                solicitud(4, "Surco", "Puente Primavera", null), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("eliminada");
    }

    @Test
    void rutaConViajeEnProgresoNoSePuedeEditar() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.EN_PROGRESO)));
        assertThatThrownBy(() -> servicio.actualizar(1L,
                solicitud(4, "Surco", "Puente Primavera", null), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("en progreso");
    }

    @Test
    void rutaDeOtroConductorNoSePuedeEditar() {
        when(rutaRepository.findByIdAndConductorId(2L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.actualizar(2L,
                solicitud(4, "Surco", "Puente Primavera", null), false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestDirectoIncompletoDevuelveErrorDeCampos() {
        assertThatThrownBy(() -> servicio.actualizar(1L,
                new RutaRequest("", null, "", null, null), false))
                .isInstanceOf(CamposInvalidosException.class);
        verify(rutaRepository, never()).save(any());
    }
}
