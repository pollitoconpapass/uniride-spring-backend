package com.uniride.repositories;

import static org.assertj.core.api.Assertions.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class MisSolicitudesUs13RepositoryTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PenalidadRepository penalidades;

    Usuario usuario(String correo, Rol rol) {
        return usuarios.save(Usuario.builder().correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Ana").apellidos("Perez")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(rol).aceptaTerminos(true).build());
    }

    Viaje viaje(Usuario conductor, LocalDate fecha, EstadoViaje estado) {
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor).origen("Surco")
                .destino("UPC").capacidadMaxima(3).puntosReferencia("Puente")
                .estado(EstadoRuta.ACTIVA).build());
        return viajes.save(Viaje.builder().ruta(ruta).fecha(fecha).hora(LocalTime.NOON)
                .dia("Lunes").estado(estado).build());
    }

    Solicitud solicitud(Usuario pasajero, Viaje viaje, EstadoSolicitud estado) {
        return solicitudes.save(Solicitud.builder().pasajero(pasajero).viaje(viaje)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(estado).build());
    }

    org.springframework.data.domain.Page<Solicitud> historial(Long id, int page, int size) {
        return solicitudes.buscarHistorialUS13(id, LocalDate.now(), LocalTime.now(),
                List.of(EstadoViaje.CANCELADO, EstadoViaje.COMPLETADO, EstadoViaje.VENCIDO),
                List.of(EstadoSolicitud.CANCELADA, EstadoSolicitud.RECHAZADA),
                PageRequest.of(page, size));
    }

    @Test
    void excluyeSolicitudesAjenasYFuturasPendientes() {
        Usuario conductor=usuario("u13c1@upc.edu.pe", Rol.CONDUCTOR);
        Usuario p1=usuario("u13p1@upc.edu.pe", Rol.PASAJERO);
        Usuario p2=usuario("u13p2@upc.edu.pe", Rol.PASAJERO);
        Viaje futuro=viaje(conductor,LocalDate.now().plusDays(3),EstadoViaje.PROGRAMADO);
        Viaje pasado=viaje(conductor,LocalDate.now().minusDays(3),EstadoViaje.PROGRAMADO);
        solicitud(p1,futuro,EstadoSolicitud.PENDIENTE);
        Solicitud s1=solicitud(p1,pasado,EstadoSolicitud.ACEPTADA);
        solicitud(p2,pasado,EstadoSolicitud.ACEPTADA);
        var r=historial(p1.getId(),0,10);
        assertThat(r.getContent()).hasSize(1);
        assertThat(r.getContent().get(0).getId()).isEqualTo(s1.getId());
    }

    @Test
    void incluyeCanceladasRechazadasYViajeCanceladoAunqueSeanFuturos() {
        Usuario c=usuario("u13c2@upc.edu.pe", Rol.CONDUCTOR);
        Usuario p=usuario("u13p3@upc.edu.pe", Rol.PASAJERO);
        Viaje futuro=viaje(c,LocalDate.now().plusDays(5),EstadoViaje.PROGRAMADO);
        Viaje cancelado=viaje(c,LocalDate.now().plusDays(6),EstadoViaje.CANCELADO);
        solicitud(p,futuro,EstadoSolicitud.CANCELADA);
        solicitud(p,futuro,EstadoSolicitud.RECHAZADA);
        solicitud(p,cancelado,EstadoSolicitud.ACEPTADA);
        assertThat(historial(p.getId(),0,10).getTotalElements()).isEqualTo(3);
    }

    @Test
    void historialPaginarEnBaseDeDatos() {
        Usuario c=usuario("u13c3@upc.edu.pe", Rol.CONDUCTOR);
        Usuario p=usuario("u13p4@upc.edu.pe", Rol.PASAJERO);
        Viaje v=viaje(c,LocalDate.now().minusDays(1),EstadoViaje.COMPLETADO);
        solicitud(p,v,EstadoSolicitud.ACEPTADA);
        solicitud(p,v,EstadoSolicitud.RECHAZADA);
        solicitud(p,v,EstadoSolicitud.CANCELADA);
        assertThat(historial(p.getId(),0,2).getContent()).hasSize(2);
        assertThat(historial(p.getId(),1,2).getContent()).hasSize(1);
    }

    @Test
    void penalidadSeFiltraPorUsuarioYViaje() {
        Usuario c=usuario("u13c4@upc.edu.pe", Rol.CONDUCTOR);
        Usuario p=usuario("u13p5@upc.edu.pe", Rol.PASAJERO);
        Viaje v1=viaje(c,LocalDate.now().minusDays(1),EstadoViaje.CANCELADO);
        Viaje v2=viaje(c,LocalDate.now().minusDays(2),EstadoViaje.CANCELADO);
        penalidades.save(Penalidad.builder().viaje(v1).usuario(p).tipo(TipoPenalidad.LEVE)
                .motivo("Cancelacion tardia").build());
        penalidades.save(Penalidad.builder().viaje(v1).usuario(c).tipo(TipoPenalidad.GRAVE)
                .motivo("Cancelacion conductor").build());
        penalidades.save(Penalidad.builder().viaje(v2).usuario(p).tipo(TipoPenalidad.GRAVE)
                .motivo("Otra cancelacion").build());
        var r=penalidades.findByUsuarioIdAndViajeIdIn(p.getId(),List.of(v1.getId()));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getUsuario().getId()).isEqualTo(p.getId());
        assertThat(r.get(0).getViaje().getId()).isEqualTo(v1.getId());
    }
}
