package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.responses.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

@ExtendWith(MockitoExtension.class)
class SolicitudesRecibidasUs14ServiceTest {
    @Mock RutaRepository rutaRepository;
    @Mock SolicitudRepository solicitudRepository;
    @Mock PerfilRepository perfilRepository;
    @Mock UsuarioService usuarioService;
    SolicitudesRecibidasUs14Service service;
    Usuario conductor;
    Usuario pasajero;

    @BeforeEach
    void preparar() {
        service = new SolicitudesRecibidasUs14Service(rutaRepository, solicitudRepository,
                perfilRepository, usuarioService);
        conductor = Usuario.builder().id(7L).nombre("Carlos").build();
        pasajero = Usuario.builder().id(15L).nombre("Ana").apellidos("Lopez").build();
    }

    private Ruta ruta(long id) {
        return Ruta.builder().id(id).conductor(conductor).origen("Surco")
                .destino("UPC").estado(EstadoRuta.ACTIVA).build();
    }

    private Solicitud solicitud(long id, Ruta ruta) {
        Viaje viaje = Viaje.builder().id(90L + id).ruta(ruta)
                .fecha(LocalDate.of(2026, 11, 10)).hora(LocalTime.of(8, 0)).build();
        return Solicitud.builder().id(id).viaje(viaje).pasajero(pasajero)
                .estado(EstadoSolicitud.PENDIENTE).mensaje("Estoy cerca del origen")
                .fechaCreacion(LocalDateTime.of(2026, 11, 2, 18, 0)).build();
    }

    private void pagina(Ruta... rutas) {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaRepository.findByConductorId(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Arrays.asList(rutas), PageRequest.of(0, 10), rutas.length));
    }

    @Test
    void agrupaSolicitudesDeVariasRutasEIncluyeRutasSinInteresados() {
        Ruta primera = ruta(1L), segunda = ruta(2L);
        pagina(primera, segunda);
        when(solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(1L))
                .thenReturn(List.of(solicitud(3L, primera), solicitud(4L, primera)));
        when(solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(2L))
                .thenReturn(List.of());
        when(perfilRepository.findByUsuarioId(15L)).thenReturn(Optional.of(
                Perfil.builder().carrera("Ingenieria de Software").distrito("Surco").build()));

        var respuesta = service.porRuta(null, 0, 10);

        assertThat(respuesta.rutas().getTotalElements()).isEqualTo(2);
        assertThat(respuesta.rutas().getContent().get(0).solicitudes()).hasSize(2);
        assertThat(respuesta.rutas().getContent().get(0).solicitudes().get(0).pasajeroCarrera())
                .isEqualTo("Ingenieria de Software");
        assertThat(respuesta.rutas().getContent().get(1).solicitudes()).isEmpty();
        assertThat(respuesta.rutas().getContent().get(1).mensaje()).contains("interesados");
    }

    @Test
    void sinRutasDevuelveMensaje() {
        pagina();
        var respuesta = service.porRuta(null, 0, 10);
        assertThat(respuesta.rutas().getContent()).isEmpty();
        assertThat(respuesta.mensaje()).contains("no tienes rutas");
        verifyNoInteractions(solicitudRepository);
    }

    @Test
    void rutaSinSolicitudesOfreceRevisarDetalles() {
        Ruta r = ruta(4L);
        pagina(r);
        when(solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(4L))
                .thenReturn(List.of());
        var respuesta = service.porRuta(null, 0, 10);
        assertThat(respuesta.rutas().getContent().get(0).mensaje()).contains("Revisa");
        assertThat(respuesta.mensaje()).contains("no hay interesados");
    }

    @Test
    void filtroDeEstadoUsaConsultaCorrespondiente() {
        Ruta r = ruta(1L);
        pagina(r);
        when(solicitudRepository.findByViajeRutaIdAndEstadoOrderByFechaCreacionDesc(
                1L, EstadoSolicitud.ACEPTADA)).thenReturn(List.of());
        var respuesta = service.porRuta(EstadoSolicitud.ACEPTADA, 0, 10);
        assertThat(respuesta.mensaje()).contains("ACEPTADA");
        verify(solicitudRepository, never()).findByViajeRutaIdOrderByFechaCreacionDesc(anyLong());
    }

    @Test
    void paginaVaciaConRutasEnOtrasPaginasSugierePaginaAnterior() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaRepository.findByConductorId(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 10), 2));
        var respuesta = service.porRuta(null, 4, 10);
        assertThat(respuesta.mensaje()).contains("pagina anterior");
    }

    @Test
    void paginacionSeAplicaAntesDeConsultarSolicitudes() {
        Ruta r = ruta(1L);
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaRepository.findByConductorId(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(1, 2), 3));
        service.porRuta(null, 1, 2);
        org.mockito.ArgumentCaptor<Pageable> p = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(rutaRepository).findByConductorId(eq(7L), p.capture());
        assertThat(p.getValue().getPageNumber()).isEqualTo(1);
        assertThat(p.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void validacionDePaginacionInvalidaDa400() {
        assertThatThrownBy(() -> service.porRuta(null, -1, 10))
                .isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> service.porRuta(null, 0, 0))
                .isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> service.porRuta(null, 0, 101))
                .isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(rutaRepository);
    }

    @Test
    void muestraDetallePerfilYMensajeSoloSiLaSolicitudPerteneceAlConductor() {
        Ruta r = ruta(1L);
        Solicitud s = solicitud(51L, r);
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(solicitudRepository.findByIdAndViajeRutaConductorId(51L, 7L))
                .thenReturn(Optional.of(s));
        when(perfilRepository.findByUsuarioId(15L)).thenReturn(Optional.of(
                Perfil.builder().carrera("Ingenieria de Software")
                        .distrito("Surco").universidad("UPC").build()));
        var detalle = service.detalle(51L);
        assertThat(detalle.rutaId()).isEqualTo(1L);
        assertThat(detalle.mensajeSolicitud()).isEqualTo("Estoy cerca del origen");
        assertThat(detalle.pasajero().nombre()).isEqualTo("Ana");
        assertThat(detalle.pasajero().carrera()).isEqualTo("Ingenieria de Software");
        assertThat(detalle.pasajero().universidad()).isEqualTo("UPC");
    }

    @Test
    void detalleSolicitudAjenaEs404YSinPerfil() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(solicitudRepository.findByIdAndViajeRutaConductorId(99L, 7L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detalle(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("no encontrada");
        verifyNoInteractions(perfilRepository);
    }

    @Test
    void detalleSinPerfilNoFallaYConservaMensajeDelSolicitante() {
        Solicitud s = solicitud(51L, ruta(1L));
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(solicitudRepository.findByIdAndViajeRutaConductorId(51L, 7L))
                .thenReturn(Optional.of(s));
        when(perfilRepository.findByUsuarioId(15L)).thenReturn(Optional.empty());
        var detalle = service.detalle(51L);
        assertThat(detalle.pasajero().nombre()).isEqualTo("Ana");
        assertThat(detalle.pasajero().carrera()).isNull();
        assertThat(detalle.mensaje()).contains("no ha completado");
    }
}
