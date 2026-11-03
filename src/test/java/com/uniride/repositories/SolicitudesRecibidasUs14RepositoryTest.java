package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.*;
import com.uniride.enums.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class SolicitudesRecibidasUs14RepositoryTest {
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired RutaRepository rutaRepository;
    @Autowired ViajeRepository viajeRepository;
    @Autowired SolicitudRepository solicitudRepository;

    private Usuario usuario(String correo, String telefono, Rol rol) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo).contrasenaHash("hash")
                .nombre("Estudiante").apellidos("UPC").telefono(telefono)
                .rolPrincipal(rol).aceptaTerminos(true).build());
    }

    private Ruta ruta(Usuario conductor, String origen) {
        return rutaRepository.save(Ruta.builder().conductor(conductor).origen(origen)
                .destino("UPC").capacidadMaxima(4).puntosReferencia("Parque")
                .estado(EstadoRuta.ACTIVA).build());
    }

    private Viaje viaje(Ruta ruta) {
        return viajeRepository.save(Viaje.builder().ruta(ruta).fecha(LocalDate.now().plusDays(5))
                .hora(LocalTime.of(8, 0)).dia("Lunes").estado(EstadoViaje.PROGRAMADO).build());
    }

    private Solicitud solicitud(Viaje viaje, Usuario pasajero, EstadoSolicitud estado) {
        return solicitudRepository.save(Solicitud.builder().viaje(viaje).pasajero(pasajero)
                .preferenciaCompensacion(TipoCompensacion.DINERO).estado(estado)
                .mensaje("Hola, estoy interesado").build());
    }

    @Test
    void agrupaPorRutaIncluyendoSolicitudesDeVariosViajes() {
        Usuario conductor = usuario("conductor-us14@upc.edu.pe", "999991141", Rol.CONDUCTOR);
        Usuario pasajero = usuario("pasajero-us14@upc.edu.pe", "999991142", Rol.PASAJERO);
        Ruta r1 = ruta(conductor, "Surco");
        Ruta r2 = ruta(conductor, "Lima");
        solicitud(viaje(r1), pasajero, EstadoSolicitud.PENDIENTE);
        solicitud(viaje(r1), pasajero, EstadoSolicitud.ACEPTADA);
        solicitud(viaje(r2), pasajero, EstadoSolicitud.RECHAZADA);
        assertThat(solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(r1.getId()))
                .hasSize(2);
        assertThat(solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(r2.getId()))
                .hasSize(1);
    }

    @Test
    void filtroPorEstadoSoloDevuelveCoincidentes() {
        Usuario conductor = usuario("conductor2-us14@upc.edu.pe", "999991143", Rol.CONDUCTOR);
        Usuario pasajero = usuario("pasajero2-us14@upc.edu.pe", "999991144", Rol.PASAJERO);
        Ruta r = ruta(conductor, "Callao");
        Viaje v = viaje(r);
        solicitud(v, pasajero, EstadoSolicitud.PENDIENTE);
        solicitud(v, pasajero, EstadoSolicitud.RECHAZADA);
        List<Solicitud> pendientes = solicitudRepository
                .findByViajeRutaIdAndEstadoOrderByFechaCreacionDesc(r.getId(), EstadoSolicitud.PENDIENTE);
        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.get(0).getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
    }

    @Test
    void detalleRestringidoAlConductorPropietario() {
        Usuario conductor = usuario("conductor3-us14@upc.edu.pe", "999991145", Rol.CONDUCTOR);
        Usuario otro = usuario("conductor4-us14@upc.edu.pe", "999991146", Rol.CONDUCTOR);
        Usuario pasajero = usuario("pasajero3-us14@upc.edu.pe", "999991147", Rol.PASAJERO);
        Solicitud s = solicitud(viaje(ruta(conductor, "Surco")), pasajero, EstadoSolicitud.PENDIENTE);
        assertThat(solicitudRepository.findByIdAndViajeRutaConductorId(s.getId(), conductor.getId()))
                .isPresent();
        assertThat(solicitudRepository.findByIdAndViajeRutaConductorId(s.getId(), otro.getId()))
                .isEmpty();
    }
}
