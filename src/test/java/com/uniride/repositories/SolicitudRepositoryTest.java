package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
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
}
