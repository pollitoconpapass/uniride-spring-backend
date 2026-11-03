package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.enums.TipoPenalidad;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class IntegridadEliminacionViajesRepositoryTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PenalidadRepository penalidades;

    private Usuario crearUsuario(String correo, Rol rol) {
        return usuarios.save(Usuario.builder().correoInstitucional(correo)
                .contrasenaHash("hashdeprueba").nombre("Usuario")
                .apellidos("UniRide").telefono(rol == Rol.CONDUCTOR ? "911111111" : "922222222")
                .rolPrincipal(rol).aceptaTerminos(true).build());
    }

    private Viaje crearViaje(Usuario conductor) {
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor)
                .origen("Surco").destino("UPC").puntosReferencia("Av. Principal")
                .capacidadMaxima(3).estado(EstadoRuta.ACTIVA).build());
        return viajes.save(Viaje.builder().ruta(ruta).fecha(LocalDate.now().plusDays(3))
                .hora(LocalTime.of(7, 0)).dia("Viernes").build());
    }

    @Test
    void detectaCualquierSolicitudInclusoRechazada() {
        Usuario conductor = crearUsuario("conductor.eliminar@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("pasajero.eliminar@upc.edu.pe", Rol.PASAJERO);
        Viaje viaje = crearViaje(conductor);
        assertThat(solicitudes.existsByViajeId(viaje.getId())).isFalse();
        solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(EstadoSolicitud.RECHAZADA).mensaje("No puedo viajar").build());
        assertThat(solicitudes.existsByViajeId(viaje.getId())).isTrue();
    }

    @Test
    void detectaPenalidadAsociadaAlViaje() {
        Usuario conductor = crearUsuario("conductor.penalidad@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);
        assertThat(penalidades.existsByViajeId(viaje.getId())).isFalse();
        penalidades.save(Penalidad.builder().usuario(conductor).viaje(viaje)
                .tipo(TipoPenalidad.LEVE).motivo("Cancelacion tardia").build());
        assertThat(penalidades.existsByViajeId(viaje.getId())).isTrue();
    }
}
