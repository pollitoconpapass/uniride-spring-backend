package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.uniride.dto.requests.ViajeRequest;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class ViajeHardeningTest {
    ViajeRepository viajes = mock(ViajeRepository.class);
    RutaRepository rutas = mock(RutaRepository.class);
    SolicitudRepository solicitudes = mock(SolicitudRepository.class);
    PenalidadRepository penalidades = mock(PenalidadRepository.class);
    ViajeMapper mapper = mock(ViajeMapper.class);
    UsuarioService usuarios = mock(UsuarioService.class);
    NotificacionService avisos = mock(NotificacionService.class);
    ViajeService servicio = new ViajeService(viajes, rutas, solicitudes, penalidades, mapper, usuarios, avisos);
    LocalDateTime ahora = LocalDateTime.of(2026, 11, 5, 10, 0);
    Usuario conductor = Usuario.builder().id(1L).build();
    Usuario pasajero = Usuario.builder().id(2L).build();
    Ruta ruta = Ruta.builder().id(3L).conductor(conductor).estado(EstadoRuta.ACTIVA).build();
    Viaje viaje = Viaje.builder().id(4L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
            .fecha(ahora.toLocalDate()).hora(ahora.toLocalTime()).build();

    @BeforeEach void preparar() {
        ReflectionTestUtils.setField(servicio, "clock", Clock.fixed(
                ahora.atZone(com.uniride.config.TiempoConfig.LIMA).toInstant(), com.uniride.config.TiempoConfig.LIMA));
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(rutas.findByIdAndConductorId(3L, 1L)).thenReturn(Optional.of(ruta));
        when(viajes.bloquearParaConductor(4L, 1L)).thenReturn(Optional.of(viaje));
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        when(viajes.save(any())).thenAnswer(i -> i.getArgument(0));
        when(mapper.toViaje(any())).thenReturn(viaje);
    }

    @ParameterizedTest @CsvSource({"-1,false", "0,false", "719,false", "720,true", "721,true"})
    void crearRespetaFechaHoraYPlazo(long minutos, boolean valido) {
        LocalDateTime salida = ahora.plusMinutes(minutos);
        ViajeRequest request = new ViajeRequest(salida.toLocalDate(), salida.toLocalTime());
        if (valido) servicio.crear(3L, request);
        else {
            assertThatThrownBy(() -> servicio.crear(3L, request)).isInstanceOf(RuntimeException.class);
            verify(viajes, never()).save(any());
            verifyNoInteractions(avisos);
        }
    }

    @Test void reprogramacionSinConfirmarTambienExigeDoceHoras() {
        viaje.setFecha(ahora.plusDays(2).toLocalDate());
        assertThatThrownBy(() -> servicio.actualizar(4L,
                new ViajeRequest(ahora.toLocalDate(), ahora.plusHours(2).toLocalTime()), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test void noIniciaAntesPeroSiExactamenteALaSalida() {
        viaje.setConfirmado(true);
        viaje.setHora(ahora.plusSeconds(1).toLocalTime());
        assertThatThrownBy(() -> servicio.iniciar(4L)).isInstanceOf(BusinessException.class);
        viaje.setHora(ahora.toLocalTime());
        servicio.iniciar(4L);
        assertThat(viaje.getEstado()).isEqualTo(EstadoViaje.EN_PROGRESO);
    }

    @Test void vencimientoRecuperaSalidasPasadasYEsIdempotente() {
        viaje.setFecha(ahora.minusDays(1).toLocalDate());
        when(viajes.bloquearVencidos(any(), any(), any())).thenReturn(List.of(viaje));
        servicio.vencerAutomaticamente();
        servicio.vencerAutomaticamente();
        assertThat(viaje.getEstado()).isEqualTo(EstadoViaje.VENCIDO);
        verify(penalidades, times(1)).save(any());
        verify(avisos, times(1)).notificar(eq(conductor), any(), anyString(), anyString());
        verify(solicitudes, never()).delete(any());
    }

    @ParameterizedTest @CsvSource({"720,PROGRAMADO", "719,VENCIDO"})
    void confirmarRespetaElLimiteExactoDeDoceHoras(long minutos, EstadoViaje estado) {
        LocalDateTime salida = ahora.plusMinutes(minutos);
        viaje.setFecha(salida.toLocalDate()); viaje.setHora(salida.toLocalTime());
        servicio.confirmar(4L);
        assertThat(viaje.getEstado()).isEqualTo(estado);
        assertThat(viaje.isConfirmado()).isEqualTo(estado == EstadoViaje.PROGRAMADO);
    }

    @ParameterizedTest @CsvSource({"1200,true", "1201,false", "720,true", "719,false"})
    void recordatorioRespetaVentanaExactaDeVeinteADoceHoras(long minutos, boolean enviado) {
        LocalDateTime salida = ahora.plusMinutes(minutos);
        viaje.setFecha(salida.toLocalDate()); viaje.setHora(salida.toLocalTime());
        when(viajes.buscarPendientesDeConfirmacionAutomaticos(any(),any(),any())).thenReturn(List.of(viaje));
        servicio.enviarRecordatoriosAutomaticos();
        assertThat(viaje.isRecordatorioEnviado()).isEqualTo(enviado);
    }

    @Test void pasajeroAutorPuedeReintentarSinAceptadaNiEfectosDuplicados() {
        when(usuarios.usuarioActual()).thenReturn(pasajero);
        when(solicitudes.existsByViajeIdAndPasajeroIdAndEstadoIn(eq(4L), eq(2L), any())).thenReturn(true);
        viaje.setFecha(ahora.plusDays(2).toLocalDate());
        servicio.cancelar(4L);
        assertThat(viaje.getCanceladoPor()).isSameAs(pasajero);
        assertThat(viaje.getFechaCancelacion()).isEqualTo(ahora);
        when(solicitudes.existsByViajeIdAndPasajeroIdAndEstadoIn(eq(4L), eq(2L), any())).thenReturn(false);
        clearInvocations(avisos, penalidades, viajes);
        servicio.cancelar(4L);
        verifyNoInteractions(avisos, penalidades);
        verify(viajes, never()).save(any());
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(9L).build());
        assertThatThrownBy(() -> servicio.cancelar(4L)).isInstanceOf(NoPermitidoException.class);
    }

    @ParameterizedTest @CsvSource({"239,GRAVE", "240,LEVE", "1440,LEVE", "1441,NINGUNA"})
    void limitesDePenalidadExactos(long minutos, String tipo) {
        LocalDateTime salida = ahora.plusMinutes(minutos);
        viaje.setFecha(salida.toLocalDate()); viaje.setHora(salida.toLocalTime());
        servicio.cancelar(4L);
        if (tipo.equals("NINGUNA")) verifyNoInteractions(penalidades);
        else verify(penalidades).save(argThat(p -> p.getTipo().name().equals(tipo)));
    }
}
