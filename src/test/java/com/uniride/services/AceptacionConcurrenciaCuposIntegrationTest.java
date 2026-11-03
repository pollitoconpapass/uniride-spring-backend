package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import com.uniride.repositories.AcuerdoCompensacionRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/** Verifica el bloqueo con transacciones reales de H2, no solo con mocks. */
@SpringBootTest
class AceptacionConcurrenciaCuposIntegrationTest {
    @Autowired private SolicitudService servicio;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private RutaRepository rutas;
    @Autowired private ViajeRepository viajes;
    @Autowired private SolicitudRepository solicitudes;
    @Autowired private AcuerdoCompensacionRepository acuerdos;
    @MockitoBean private UsuarioService usuarioService;
    @MockitoBean private NotificacionService notificacionService;

    private static final AtomicLong TEL = new AtomicLong(920000000L);

    private Usuario usuario(Rol rol) {
        String unico = UUID.randomUUID().toString().replace("-", "");
        return usuarios.save(Usuario.builder()
                .correoInstitucional(unico + "@upc.edu.pe")
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Prueba").apellidos("Concurrencia")
                .telefono(Long.toString(TEL.incrementAndGet()))
                .rolPrincipal(rol).aceptaTerminos(true).build());
    }

    private Viaje crearViaje(Usuario conductor, int capacidad) {
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor)
                .origen("Surco").destino("UPC").puntosReferencia("Av. Primavera")
                .capacidadMaxima(capacidad).estado(EstadoRuta.ACTIVA).build());
        return viajes.save(Viaje.builder().ruta(ruta).fecha(LocalDate.now().plusDays(3))
                .hora(LocalTime.of(9, 0)).dia("Lunes")
                .estado(EstadoViaje.PROGRAMADO).build());
    }

    private Solicitud solicitud(Viaje viaje, Usuario pasajero) {
        return solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero)
                .estado(EstadoSolicitud.PENDIENTE)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .mensaje("Quiero viajar").build());
    }

    private boolean aceptarEnHilo(Long solicitudId, CountDownLatch empezar) throws Exception {
        empezar.await(5, TimeUnit.SECONDS);
        try {
            servicio.aceptar(solicitudId);
            return true;
        } catch (BusinessException e) {
            return false; // 409 esperado cuando el ultimo cupo ya se lleno.
        }
    }

    private void ejecutarDosAceptaciones(Long primera, Long segunda,
            Viaje viaje, boolean mismoId) throws Exception {
        long acuerdosAntes = acuerdos.count();
        CountDownLatch empezar = new CountDownLatch(1);
        ExecutorService ejecutor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> tareaA = () -> aceptarEnHilo(primera, empezar);
            Callable<Boolean> tareaB = () -> aceptarEnHilo(segunda, empezar);
            Future<Boolean> a = ejecutor.submit(tareaA);
            Future<Boolean> b = ejecutor.submit(tareaB);
            empezar.countDown();
            boolean aceptadaA = a.get(12, TimeUnit.SECONDS);
            boolean aceptadaB = b.get(12, TimeUnit.SECONDS);
            assertThat((aceptadaA ? 1 : 0) + (aceptadaB ? 1 : 0)).isEqualTo(1);
            assertThat(viajes.findById(viaje.getId()).orElseThrow().getPasajeros()).isEqualTo(1);
            assertThat(acuerdos.count()).isEqualTo(acuerdosAntes + 1);
            if (!mismoId) {
                long aceptadas = solicitudes.findById(primera).orElseThrow().getEstado()
                        .equals(EstadoSolicitud.ACEPTADA) ? 1 : 0;
                aceptadas += solicitudes.findById(segunda).orElseThrow().getEstado()
                        .equals(EstadoSolicitud.ACEPTADA) ? 1 : 0;
                assertThat(aceptadas).isEqualTo(1);
            }
        } finally {
            ejecutor.shutdownNow();
        }
    }

    @Test
    void dosSolicitudesCompitenPorUltimoCupoSinSobrecupo() throws Exception {
        Usuario conductor = usuario(Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor, 1);
        Solicitud a = solicitud(viaje, usuario(Rol.PASAJERO));
        Solicitud b = solicitud(viaje, usuario(Rol.PASAJERO));
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        ejecutarDosAceptaciones(a.getId(), b.getId(), viaje, false);
    }

    @Test
    void dosAceptacionesDeLaMismaSolicitudNoDuplicanAcuerdo() throws Exception {
        Usuario conductor = usuario(Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor, 2);
        Solicitud a = solicitud(viaje, usuario(Rol.PASAJERO));
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        ejecutarDosAceptaciones(a.getId(), a.getId(), viaje, true);
    }
}
