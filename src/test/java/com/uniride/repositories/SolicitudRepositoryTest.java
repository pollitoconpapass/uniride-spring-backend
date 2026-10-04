package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.enums.TipoPenalidad;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class SolicitudRepositoryTest {

    @Autowired
    private SolicitudRepository solicitudRepository;

    @Autowired
    private ViajeRepository viajeRepository;

    @Autowired
    private RutaRepository rutaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PenalidadRepository penalidadRepository;

    @Autowired
    private MetodoCompensacionRepository metodoCompensacionRepository;

    private Usuario crearUsuario(String correo, Rol rol) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Lucia")
                .apellidos("Mendoza")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(rol)
                .aceptaTerminos(true)
                .build());
    }

    private Viaje crearViaje(Usuario conductor) {
        Ruta ruta = rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen("San Borja")
                .destino("UPC")
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa")
                .estado(EstadoRuta.ACTIVA)
                .build());
        return viajeRepository.save(Viaje.builder()
                .ruta(ruta)
                .fecha(LocalDate.of(2026, 10, 5))
                .hora(LocalTime.of(7, 30))
                .dia("Lunes")
                .build());
    }

    private Solicitud crearSolicitud(Viaje viaje, Usuario pasajero, EstadoSolicitud estado) {
        return Solicitud.builder()
                .viaje(viaje)
                .pasajero(pasajero)
                .mensaje("Me interesa el viaje")
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(estado)
                .build();
    }

    private Viaje crearViajeConEstado(Usuario conductor, EstadoViaje estado) {
        Ruta ruta = rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen("San Borja")
                .destino("UPC")
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa")
                .estado(EstadoRuta.ACTIVA)
                .build());
        return viajeRepository.save(Viaje.builder()
                .ruta(ruta)
                .fecha(LocalDate.of(2026, 10, 5))
                .hora(LocalTime.of(7, 30))
                .dia("Lunes")
                .estado(estado)
                .build());
    }

    @Test
    void crearSolicitudConEstadoPorDefecto() {
        Usuario conductor = crearUsuario("sol1@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol2@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);

        Solicitud guardada = solicitudRepository.save(crearSolicitud(viaje, pasajero, null));

        assertThat(guardada.getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(guardada.getFechaCreacion()).isNotNull();
    }

    @Test
    void contarSolicitudesAceptadasParaCupos() {
        Usuario conductor = crearUsuario("sol3@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol4@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.ACEPTADA));
        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol5@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.ACEPTADA));
        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol6@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.PENDIENTE));

        long aceptadas = solicitudRepository.countByViajeIdAndEstado(viaje.getId(), EstadoSolicitud.ACEPTADA);
        long pendientes = solicitudRepository.countByViajeIdAndEstado(viaje.getId(), EstadoSolicitud.PENDIENTE);

        assertThat(aceptadas).isEqualTo(2);
        assertThat(pendientes).isEqualTo(1);
    }

    @Test
    void detectarSolicitudDuplicada() {
        Usuario conductor = crearUsuario("sol7@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol8@upc.edu.pe", Rol.PASAJERO);
        Usuario otro = crearUsuario("sol9@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);
        solicitudRepository.save(crearSolicitud(viaje, pasajero, EstadoSolicitud.PENDIENTE));

        List<EstadoSolicitud> bloqueantes = List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.ACEPTADA);

        assertThat(solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                viaje.getId(), pasajero.getId(), bloqueantes)).isTrue();
        assertThat(solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                viaje.getId(), otro.getId(), bloqueantes)).isFalse();
    }

    @Test
    void buscarMisSolicitudesOrdenadasYPorEstado() {
        Usuario conductor = crearUsuario("sol10@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol11@upc.edu.pe", Rol.PASAJERO);
        Usuario otro = crearUsuario("sol12@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);

        Solicitud reciente = solicitudRepository.save(crearSolicitud(viaje, pasajero, EstadoSolicitud.PENDIENTE));
        reciente.setFechaCreacion(LocalDateTime.of(2026, 10, 2, 10, 0));
        solicitudRepository.save(reciente);

        Solicitud antigua = solicitudRepository.save(crearSolicitud(viaje, pasajero, EstadoSolicitud.ACEPTADA));
        antigua.setFechaCreacion(LocalDateTime.of(2026, 10, 1, 10, 0));
        solicitudRepository.save(antigua);

        solicitudRepository.save(crearSolicitud(viaje, otro, EstadoSolicitud.PENDIENTE));

        Page<Solicitud> todas = solicitudRepository.findByPasajeroId(pasajero.getId(),
                PageRequest.of(0, 10, Sort.by("fechaCreacion").descending()));
        Page<Solicitud> pendientes = solicitudRepository.buscarPorPasajeroYEstado(
                pasajero.getId(), EstadoSolicitud.PENDIENTE, PageRequest.of(0, 10));

        assertThat(todas.getContent()).hasSize(2);
        assertThat(todas.getContent().get(0).getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(todas.getContent().get(1).getEstado()).isEqualTo(EstadoSolicitud.ACEPTADA);
        assertThat(pendientes.getContent()).hasSize(1);
    }

    @Test
    void buscarSolicitudPorIdYPasajero() {
        Usuario conductor = crearUsuario("sol13@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol14@upc.edu.pe", Rol.PASAJERO);
        Usuario otro = crearUsuario("sol15@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);
        Solicitud solicitud = solicitudRepository.save(crearSolicitud(viaje, pasajero, EstadoSolicitud.PENDIENTE));

        assertThat(solicitudRepository.findByIdAndPasajeroId(solicitud.getId(), pasajero.getId())).isPresent();
        assertThat(solicitudRepository.findByIdAndPasajeroId(solicitud.getId(), otro.getId())).isEmpty();
    }

    @Test
    void buscarSolicitudPorIdYConductorDeLaRuta() {
        Usuario conductor = crearUsuario("sol16@upc.edu.pe", Rol.CONDUCTOR);
        Usuario otroConductor = crearUsuario("sol17@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol18@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);
        Solicitud solicitud = solicitudRepository.save(crearSolicitud(viaje, pasajero, EstadoSolicitud.PENDIENTE));

        assertThat(solicitudRepository.findByIdAndViajeRutaConductorId(
                solicitud.getId(), conductor.getId())).isPresent();
        assertThat(solicitudRepository.findByIdAndViajeRutaConductorId(
                solicitud.getId(), otroConductor.getId())).isEmpty();
    }

    @Test
    void buscarSolicitudesRecibidasPorViaje() {
        Usuario conductor = crearUsuario("sol19@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        Solicitud reciente = solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol20@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.ACEPTADA));
        reciente.setFechaCreacion(LocalDateTime.of(2026, 10, 2, 10, 0));
        solicitudRepository.save(reciente);

        Solicitud antigua = solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol21@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.PENDIENTE));
        antigua.setFechaCreacion(LocalDateTime.of(2026, 10, 1, 10, 0));
        solicitudRepository.save(antigua);

        Page<Solicitud> todas = solicitudRepository.findByViajeIdOrderByFechaCreacionDesc(
                viaje.getId(), PageRequest.of(0, 10));
        Page<Solicitud> pendientes = solicitudRepository.buscarPorViajeYEstado(
                viaje.getId(), EstadoSolicitud.PENDIENTE, PageRequest.of(0, 10));

        assertThat(todas.getContent()).hasSize(2);
        assertThat(todas.getContent().get(0).getEstado()).isEqualTo(EstadoSolicitud.ACEPTADA);
        assertThat(todas.getContent().get(1).getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(pendientes.getContent()).hasSize(1);
    }

    @Test
    void buscarSolicitudesPorViajeYEstadosParaNotificar() {
        Usuario conductor = crearUsuario("sol22@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol23@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.ACEPTADA));
        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol24@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.PENDIENTE));
        solicitudRepository.save(crearSolicitud(viaje,
                crearUsuario("sol25@upc.edu.pe", Rol.PASAJERO), EstadoSolicitud.RECHAZADA));

        List<Solicitud> involucradas = solicitudRepository.findByViajeIdAndEstadoIn(
                viaje.getId(), List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.ACEPTADA));
        List<Solicitud> aceptadas = solicitudRepository.findByViajeIdAndEstadoIn(
                viaje.getId(), List.of(EstadoSolicitud.ACEPTADA));

        assertThat(involucradas).hasSize(2);
        assertThat(aceptadas).hasSize(1);
        assertThat(aceptadas.get(0).getEstado()).isEqualTo(EstadoSolicitud.ACEPTADA);
    }

    @Test
    void contarRealizadosYFrecuenciaComoPasajero() {
        Usuario conductor = crearUsuario("sol26@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol27@upc.edu.pe", Rol.PASAJERO);
        Viaje completado = crearViajeConEstado(conductor, EstadoViaje.COMPLETADO);
        Viaje enProgreso = crearViajeConEstado(conductor, EstadoViaje.EN_PROGRESO);

        solicitudRepository.save(crearSolicitud(completado, pasajero, EstadoSolicitud.ACEPTADA));
        solicitudRepository.save(crearSolicitud(enProgreso, pasajero, EstadoSolicitud.ACEPTADA));

        long realizados = solicitudRepository.contarRealizadosComoPasajero(
                pasajero.getId(), EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);
        List<Object[]> frecuencia = solicitudRepository.frecuenciaPorDiaComoPasajero(
                pasajero.getId(), EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);

        assertThat(realizados).isEqualTo(1);
        assertThat(frecuencia).hasSize(1);
        assertThat((String) frecuencia.get(0)[0]).isEqualTo("Lunes");
        assertThat(((Number) frecuencia.get(0)[1]).longValue()).isEqualTo(1);
    }

    @Test
    void contarViajesCanceladosPorTerceros() {
        Usuario conductor = crearUsuario("sol28@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol29@upc.edu.pe", Rol.PASAJERO);
        Viaje sinMiPenalidad = crearViajeConEstado(conductor, EstadoViaje.CANCELADO);
        Viaje conMiPenalidad = crearViajeConEstado(conductor, EstadoViaje.CANCELADO);

        solicitudRepository.save(crearSolicitud(sinMiPenalidad, pasajero, EstadoSolicitud.CANCELADA));
        solicitudRepository.save(crearSolicitud(conMiPenalidad, pasajero, EstadoSolicitud.CANCELADA));
        penalidadRepository.save(Penalidad.builder()
                .usuario(pasajero)
                .viaje(conMiPenalidad)
                .tipo(TipoPenalidad.GRAVE)
                .motivo("Canceló el viaje con pocas horas")
                .build());

        long total = solicitudRepository.contarViajesCanceladosComoPasajero(
                pasajero.getId(), EstadoViaje.CANCELADO);
        long porTerceros = solicitudRepository.contarViajesCanceladosPorTerceros(
                pasajero.getId(), EstadoViaje.CANCELADO);

        assertThat(total).isEqualTo(2);
        assertThat(porTerceros).isEqualTo(1);
    }

    @Test
    void guardarSolicitudConMetodoCompensacion() {
        Usuario conductor = crearUsuario("sol30@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("sol31@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViajeConEstado(conductor, EstadoViaje.PROGRAMADO);
        MetodoCompensacion metodo = metodoCompensacionRepository.save(MetodoCompensacion.builder()
                .usuario(pasajero)
                .tipo(TipoCompensacion.DINERO)
                .descripcion("Yape - Juan")
                .activo(true)
                .build());

        Solicitud guardada = solicitudRepository.save(Solicitud.builder()
                .viaje(viaje)
                .pasajero(pasajero)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(EstadoSolicitud.PENDIENTE)
                .metodoCompensacion(metodo)
                .build());

        Solicitud relectura = solicitudRepository.findById(guardada.getId()).orElseThrow();
        assertThat(relectura.getMetodoCompensacion()).isNotNull();
        assertThat(relectura.getMetodoCompensacion().getId()).isEqualTo(metodo.getId());
        assertThat(relectura.getMetodoCompensacion().getDescripcion()).isEqualTo("Yape - Juan");
    }
}
