package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Requiere transacciones reales (H2) y dos hilos, no solo Mockito. */
@SpringBootTest
class CancelacionAceptacionConcurrencia113IntegrationTest {
    @Autowired SolicitudService solicitudService;
    @Autowired ViajeService viajeService;
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @MockitoBean UsuarioService usuarioService;
    @MockitoBean NotificacionService notificaciones;

    private static final AtomicLong TEL = new AtomicLong(960000000L);

    private Usuario usuario(Rol rol) {
        String unico = UUID.randomUUID().toString().replace("-", "");
        return usuarios.save(Usuario.builder()
                .correoInstitucional(unico + "@upc.edu.pe")
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Prueba").apellidos("Integracion")
                .telefono(Long.toString(TEL.incrementAndGet()))
                .rolPrincipal(rol).aceptaTerminos(true).build());
    }

    private Viaje crearViaje(Usuario conductor) {
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor)
                .origen("Surco").destino("UPC").puntosReferencia("Primavera")
                .capacidadMaxima(2).estado(EstadoRuta.ACTIVA).build());
        return viajes.save(Viaje.builder().ruta(ruta).fecha(LocalDate.now().plusDays(3))
                .hora(LocalTime.of(9, 0)).dia("Lunes")
                .estado(EstadoViaje.PROGRAMADO).build());
    }

    @Test
    void cancelarYAceptarSimultaneamenteNoDejaPasajerosAceptadosEnViajeCancelado() throws Exception {
        Usuario conductor = usuario(Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);
        Solicitud solicitud = solicitudes.save(Solicitud.builder()
                .viaje(viaje).pasajero(usuario(Rol.PASAJERO))
                .estado(EstadoSolicitud.PENDIENTE)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .mensaje("Me interesa").build());
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salir = new CountDownLatch(1);
        try {
            Future<Boolean> aceptar = pool.submit(() -> {
                salir.await(5, TimeUnit.SECONDS);
                try {
                    solicitudService.aceptar(solicitud.getId());
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            });
            Future<?> cancelar = pool.submit(() -> {
                salir.await(5, TimeUnit.SECONDS);
                viajeService.cancelar(viaje.getId());
                return null;
            });
            salir.countDown();
            aceptar.get(15, TimeUnit.SECONDS);
            cancelar.get(15, TimeUnit.SECONDS);
            Viaje finalizado = viajes.findById(viaje.getId()).orElseThrow();
            assertThat(finalizado.getEstado()).isEqualTo(EstadoViaje.CANCELADO);
            assertThat(finalizado.getPasajeros()).isZero();
            assertThat(solicitudes.findById(solicitud.getId()).orElseThrow().getEstado())
                    .isEqualTo(EstadoSolicitud.CANCELADA);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dosSolicitudesSimultaneasDelMismoPasajeroNoSeDuplican() throws Exception {
        Usuario conductor = usuario(Rol.CONDUCTOR);
        Usuario pasajero = usuario(Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        SolicitudRequest request = new SolicitudRequest("Viaje a la UPC", TipoCompensacion.DINERO, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salir = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> trabajo = () -> {
                salir.await(5, TimeUnit.SECONDS);
                try {
                    solicitudService.crear(viaje.getId(), request);
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(trabajo);
            Future<Boolean> b = pool.submit(trabajo);
            salir.countDown();
            assertThat((a.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(15, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            List<Solicitud> guardadas = solicitudes.findByViajeIdAndEstadoIn(
                    viaje.getId(), List.of(EstadoSolicitud.PENDIENTE));
            assertThat(guardadas).hasSize(1);
            assertThat(guardadas.get(0).getPasajero().getId()).isEqualTo(pasajero.getId());
        } finally {
            pool.shutdownNow();
        }
    }
}
