package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class CierreTotal114AccesoYMetodosRepositoryTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired MetodoCompensacionRepository metodos;

    private Usuario usuario(String correo, String telefono, Rol rol) {
        return usuarios.save(Usuario.builder().correoInstitucional(correo)
                .contrasenaHash("hashed").nombre("Nadia").apellidos("Ruiz")
                .telefono(telefono).rolPrincipal(rol).aceptaTerminos(true).build());
    }

    @Test
    void consultaDePerfilSoloAutorizaConductorRelacionadaConSolicitud() {
        Usuario conductor = usuario("c@upc.edu.pe", "999123451", Rol.CONDUCTOR);
        Usuario ajeno = usuario("a@upc.edu.pe", "999123452", Rol.CONDUCTOR);
        Usuario pasajero = usuario("p@upc.edu.pe", "999123453", Rol.PASAJERO);
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor).origen("Surco")
                .destino("UPC").capacidadMaxima(2).puntosReferencia("Av. Lima")
                .estado(EstadoRuta.ACTIVA).build());
        Viaje viaje = viajes.save(Viaje.builder().ruta(ruta)
                .fecha(LocalDate.now().plusDays(3)).hora(LocalTime.NOON).dia("Lunes").build());
        solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero)
                .mensaje("Me interesa").estado(EstadoSolicitud.PENDIENTE)
                .preferenciaCompensacion(TipoCompensacion.DINERO).build());
        assertThat(solicitudes.existeSolicitudPasajeroParaConductor(
                pasajero.getId(), conductor.getId())).isTrue();
        assertThat(solicitudes.existeSolicitudPasajeroParaConductor(
                pasajero.getId(), ajeno.getId())).isFalse();
    }

    @Test
    void todoMetodoUsadoEnUnaSolicitudQuedaProtegido() {
        Usuario conductor = usuario("d@upc.edu.pe", "999123454", Rol.CONDUCTOR);
        Usuario pasajero = usuario("q@upc.edu.pe", "999123455", Rol.PASAJERO);
        Ruta ruta = rutas.save(Ruta.builder().conductor(conductor).origen("Surco")
                .destino("UPC").capacidadMaxima(2).puntosReferencia("Av. Lima")
                .estado(EstadoRuta.ACTIVA).build());
        Viaje viaje = viajes.save(Viaje.builder().ruta(ruta)
                .fecha(LocalDate.now().plusDays(3)).hora(LocalTime.NOON).dia("Lunes").build());
        MetodoCompensacion usado = metodos.save(MetodoCompensacion.builder()
                .usuario(pasajero).tipo(TipoCompensacion.DINERO)
                .descripcion("Yape").activo(true).build());
        MetodoCompensacion libre = metodos.save(MetodoCompensacion.builder()
                .usuario(pasajero).tipo(TipoCompensacion.FAVOR)
                .descripcion("Ayuda").activo(true).build());
        solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero)
                .estado(EstadoSolicitud.CANCELADA)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .metodoCompensacion(usado).build());
        assertThat(metodos.estaAsociadoACualquierSolicitud(usado.getId())).isTrue();
        assertThat(metodos.estaAsociadoACualquierSolicitud(libre.getId())).isFalse();
    }
}
