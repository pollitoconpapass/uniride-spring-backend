package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.dto.requests.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** Obligatorio en CI; falla si PostgreSQL no está disponible. Nunca usa la base de desarrollo. */
@Tag("postgres") @SpringBootTest @ActiveProfiles("postgres")
class PostgresHardeningIntegrationTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PerfilRepository perfiles;
    @Autowired PenalidadRepository penalidades;
    @Autowired AcuerdoCompensacionRepository acuerdos;
    @Autowired PerfilRolService roles;
    @Autowired ViajeService viajeService;
    @Autowired RutaService rutaService;
    @Autowired SolicitudService solicitudService;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;
    @MockitoBean UsuarioService usuarioService;
    static AtomicLong tel = new AtomicLong(930000000);

    Usuario usuario(Rol rol) {
        Usuario u = usuarios.save(Usuario.builder().correoInstitucional(UUID.randomUUID()+"@upc.edu.pe")
                .telefono("fixture-"+UUID.randomUUID()).nombre("Ana").apellidos("Prueba")
                .contrasenaHash(encoder.encode("ClaveSegura1!")).rolPrincipal(rol).aceptaTerminos(true).build());
        u.setTelefono("+519" + String.format("%08d", u.getId()));
        u = usuarios.save(u);
        roles.asegurarPerfil(u,rol); return u;
    }
    Ruta ruta(Usuario conductor, int capacidad) {
        perfiles.save(Perfil.builder().usuario(conductor).nombre("Ana").apellidos("Prueba")
                .carrera("Software").distrito("Surco").universidad("UPC").diasDisponibles("Lunes")
                .horariosDisponibles("8:00").fechaInicioClases(LocalDate.of(2026,3,1))
                .fechaFinClases(LocalDate.of(2026,12,1)).build());
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        return rutas.save(Ruta.builder().conductor(conductor).origen("Surco").destino("UPC")
                .puntosReferencia("Puente").estado(EstadoRuta.ACTIVA).capacidadMaxima(capacidad).build());
    }
    Viaje viaje(Ruta ruta, LocalDateTime salida) {
        return viajes.save(Viaje.builder().ruta(ruta).fecha(salida.toLocalDate()).hora(salida.toLocalTime())
                .dia("Lunes").estado(EstadoViaje.PROGRAMADO).build());
    }
    Solicitud solicitud(Viaje viaje) {
        return solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(usuario(Rol.PASAJERO))
                .preferenciaCompensacion(TipoCompensacion.DINERO).estado(EstadoSolicitud.PENDIENTE).build());
    }
    boolean ejecutar(Runnable accion) {
        try { accion.run(); return true; } catch (BusinessException e) { return false; }
    }
    void paralelo(Runnable a, Runnable b) throws Exception {
        CountDownLatch inicio = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> fa=pool.submit(() -> { try { inicio.await(); a.run(); } catch(InterruptedException e){throw new RuntimeException(e);} });
            Future<?> fb=pool.submit(() -> { try { inicio.await(); b.run(); } catch(InterruptedException e){throw new RuntimeException(e);} });
            inicio.countDown(); fa.get(12,TimeUnit.SECONDS); fb.get(12,TimeUnit.SECONDS);
        }
    }

    @Test void reduccionDeCapacidadConcurrenteConAceptacionNuncaGeneraSobrecupo() throws Exception {
        Ruta r=ruta(usuario(Rol.CONDUCTOR),2);
        Viaje v=viaje(r,LocalDateTime.now(com.uniride.config.TiempoConfig.LIMA).plusDays(3));
        solicitudService.aceptar(solicitud(v).getId());
        Solicitud pendiente=solicitud(v);
        paralelo(() -> ejecutar(() -> rutaService.actualizar(r.getId(),new RutaRequest("Surco",1,"Puente",null,null),true)),
                () -> ejecutar(() -> solicitudService.aceptar(pendiente.getId())));
        long aceptados=solicitudes.countByViajeIdAndEstado(v.getId(),EstadoSolicitud.ACEPTADA);
        int capacidad=rutas.findById(r.getId()).orElseThrow().getCapacidadMaxima();
        assertThat(aceptados).isLessThanOrEqualTo(capacidad);
        assertThat(viajes.findById(v.getId()).orElseThrow().getPasajeros()).isEqualTo((int)aceptados);
    }
    @Test void eliminarRutaYCrearViajeNoDejaProgramadoEnRutaEliminada() throws Exception {
        Ruta r=ruta(usuario(Rol.CONDUCTOR),2);
        LocalDateTime salida=LocalDateTime.now(com.uniride.config.TiempoConfig.LIMA).plusDays(3);
        paralelo(() -> ejecutar(() -> rutaService.eliminar(r.getId(),true)),
                () -> ejecutar(() -> viajeService.crear(r.getId(),new ViajeRequest(salida.toLocalDate(),salida.toLocalTime()))));
        assertThat(rutas.findById(r.getId()).orElseThrow().getEstado()).isEqualTo(EstadoRuta.ELIMINADA);
        tx.executeWithoutResult(t -> assertThat(viajes.findByRutaId(r.getId())).noneMatch(v -> v.getEstado()==EstadoViaje.PROGRAMADO));
    }
    @Test void vencimientosConcurrentesYConfirmacionTardiaSoloPenalizanUnaVez() throws Exception {
        Ruta r=ruta(usuario(Rol.CONDUCTOR),2);
        Viaje v=viaje(r,LocalDateTime.now(com.uniride.config.TiempoConfig.LIMA).minusDays(1));
        paralelo(() -> viajeService.vencerAutomaticamente(), () -> ejecutar(() -> viajeService.confirmar(v.getId())));
        assertThat(viajes.findById(v.getId()).orElseThrow().getEstado()).isEqualTo(EstadoViaje.VENCIDO);
        assertThat(penalidades.findByUsuarioIdAndViajeIdIn(r.getConductor().getId(),List.of(v.getId()))).hasSize(1);
        assertThat(viajes.countByEstadoAndCanceladoPorId(EstadoViaje.CANCELADO,r.getConductor().getId())).isZero();
    }
    @Test void intentosOtpNoSeReviertenAlResponder400YBloqueoPersiste() {
        Usuario u=usuario(Rol.PASAJERO);
        u.setCodigoVerificacion(encoder.encode("123456"));
        u.setCodigoVerificacionExpiracion(LocalDateTime.now(com.uniride.config.TiempoConfig.LIMA).plusMinutes(15));
        usuarios.save(u);
        for(int i=0;i<5;i++) assertThatThrownBy(() -> auth.verificarCodigo(new VerificarCodigoRequest(u.getCorreoInstitucional(),"000000")))
                .isInstanceOf(CamposInvalidosException.class);
        assertThat(usuarios.findById(u.getId()).orElseThrow().getIntentosOtp()).isEqualTo(5);
        assertThatThrownBy(() -> auth.verificarCodigo(new VerificarCodigoRequest(u.getCorreoInstitucional(),"123456")))
                .isInstanceOf(DemasiadasSolicitudesException.class);
    }
    @Test void cuentaVerificadaNotificaTrasLiberarBloqueoSinInterbloqueoFk() {
        Usuario u=usuario(Rol.PASAJERO); u.setCuentaVerificada(true); usuarios.save(u);
        assertThatThrownBy(() -> auth.verificarCodigo(new VerificarCodigoRequest(u.getCorreoInstitucional(),"123456")))
                .isInstanceOf(BusinessException.class);
    }
}
