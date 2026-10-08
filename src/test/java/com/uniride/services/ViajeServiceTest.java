package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoPenalidad;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViajeServiceTest {

    @Mock
    private ViajeRepository viajeRepository;

    @Mock
    private RutaRepository rutaRepository;

    @Mock
    private SolicitudRepository solicitudRepository;

    @Mock
    private PenalidadRepository penalidadRepository;

    @Mock
    private ViajeMapper viajeMapper;

    @Mock
    private UsuarioService usuarioService;

    @Mock
    private NotificacionService notificacionService;

    private ViajeService viajeService;

    private Usuario conductor;
    private Viaje viaje;

    @BeforeEach
    void setUp() {
        viajeService = new ViajeService(
                viajeRepository,
                rutaRepository,
                solicitudRepository,
                penalidadRepository,
                viajeMapper,
                usuarioService,
                notificacionService
        );

        conductor = Usuario.builder()
                .id(1L)
                .nombre("Conductor")
                .build();

        Ruta ruta = Ruta.builder()
                .id(10L)
                .conductor(conductor)
                .origen("Surco")
                .destino("UPC")
                .build();

        viaje = Viaje.builder()
                .id(20L)
                .ruta(ruta)
                .dia("Jueves")
                .estado(EstadoViaje.PROGRAMADO)
                .confirmado(false)
                .build();
    }

    @Test
    void us17DebePermitirConfirmarConMasDe12HorasDeAnticipacion() {
        LocalDateTime salida = LocalDateTime.now().plusHours(13);

        viaje.setFecha(salida.toLocalDate());
        viaje.setHora(salida.toLocalTime());

        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(viajeRepository.findByIdAndRutaConductorId(20L, 1L))
                .thenReturn(Optional.of(viaje));
        when(viajeRepository.save(any(Viaje.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(solicitudRepository.findByViajeIdAndEstadoIn(any(), any()))
                .thenReturn(List.of());

        viajeService.confirmar(20L);

        assertThat(viaje.isConfirmado()).isTrue();
        assertThat(viaje.getFechaConfirmacion()).isNotNull();
        assertThat(viaje.getEstado()).isEqualTo(EstadoViaje.PROGRAMADO);

        verify(viajeRepository).save(viaje);
    }

    @Test
    void us17DebeVencerViajeSiFaltanMenosDe12Horas() {
        LocalDateTime salida = LocalDateTime.now().plusHours(10);

        viaje.setFecha(salida.toLocalDate());
        viaje.setHora(salida.toLocalTime());

        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(viajeRepository.findByIdAndRutaConductorId(20L, 1L))
                .thenReturn(Optional.of(viaje));
        when(viajeRepository.save(any(Viaje.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        viajeService.confirmar(20L);

        assertThat(viaje.isConfirmado()).isFalse();
        assertThat(viaje.getEstado()).isEqualTo(EstadoViaje.VENCIDO);

        ArgumentCaptor<Penalidad> captor =
                ArgumentCaptor.forClass(Penalidad.class);

        verify(penalidadRepository).save(captor.capture());

        assertThat(captor.getValue().getTipo())
                .isEqualTo(TipoPenalidad.LEVE);
        assertThat(captor.getValue().getUsuario())
                .isEqualTo(conductor);
    }
}