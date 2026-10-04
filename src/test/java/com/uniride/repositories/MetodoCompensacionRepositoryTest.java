package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class MetodoCompensacionRepositoryTest {

    @Autowired
    private MetodoCompensacionRepository metodoCompensacionRepository;

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
                .nombre("Ana")
                .apellidos("Torres")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(rol)
                .aceptaTerminos(true)
                .build());
    }

    private MetodoCompensacion crearMetodo(Usuario usuario, TipoCompensacion tipo, String descripcion) {
        return metodoCompensacionRepository.save(MetodoCompensacion.builder()
                .usuario(usuario)
                .tipo(tipo)
                .descripcion(descripcion)
                .activo(true)
                .build());
    }

    private Ruta crearRuta(Usuario conductor) {
        return rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen("San Borja")
                .destino("UPC")
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa")
                .estado(EstadoRuta.ACTIVA)
                .build());
    }

    private Viaje crearViaje(Ruta ruta, boolean confirmado) {
        return viajeRepository.save(Viaje.builder()
                .ruta(ruta)
                .fecha(LocalDate.of(2026, 10, 5))
                .hora(LocalTime.of(7, 30))
                .dia("Lunes")
                .confirmado(confirmado)
                .estado(EstadoViaje.PROGRAMADO)
                .build());
    }

    @Test
    void guardarYBuscarMetodosPorUsuario() {
        Usuario usuario = crearUsuario("met1@upc.edu.pe", Rol.PASAJERO);
        Usuario otro = crearUsuario("met2@upc.edu.pe", Rol.PASAJERO);
        crearMetodo(usuario, TipoCompensacion.DINERO, "Yape - Juan");
        crearMetodo(otro, TipoCompensacion.DINERO, "Plin - Ana");

        List<MetodoCompensacion> mios = metodoCompensacionRepository
                .findByUsuarioIdOrderByIdDesc(usuario.getId());

        assertThat(mios).hasSize(1);
        assertThat(mios.get(0).getDescripcion()).isEqualTo("Yape - Juan");
        assertThat(mios.get(0).isActivo()).isTrue();
        assertThat(metodoCompensacionRepository
                .findByIdAndUsuarioId(mios.get(0).getId(), usuario.getId())).isPresent();
        assertThat(metodoCompensacionRepository
                .findByIdAndUsuarioId(mios.get(0).getId(), otro.getId())).isEmpty();
    }

    @Test
    void detectarDuplicadoPorTipoYDescripcionIgnorandoMayusculas() {
        Usuario usuario = crearUsuario("met3@upc.edu.pe", Rol.PASAJERO);
        Usuario otro = crearUsuario("met4@upc.edu.pe", Rol.PASAJERO);
        crearMetodo(usuario, TipoCompensacion.DINERO, "Visa terminada en 1234");

        assertThat(metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                usuario.getId(), TipoCompensacion.DINERO, "visa terminada en 1234")).isTrue();
        assertThat(metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                usuario.getId(), TipoCompensacion.DINERO, "Otra descripción")).isFalse();
        assertThat(metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                usuario.getId(), TipoCompensacion.GASOLINA, "Visa terminada en 1234")).isFalse();
        assertThat(metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                otro.getId(), TipoCompensacion.DINERO, "Visa terminada en 1234")).isFalse();
    }

    @Test
    void detectarDuplicadoExcluyendoElPropioMetodo() {
        Usuario usuario = crearUsuario("met5@upc.edu.pe", Rol.PASAJERO);
        MetodoCompensacion metodo = crearMetodo(usuario, TipoCompensacion.DINERO, "Yape - Juan");

        assertThat(metodoCompensacionRepository
                .existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
                        usuario.getId(), TipoCompensacion.DINERO, "yape - juan", metodo.getId()))
                .isFalse();
        assertThat(metodoCompensacionRepository
                .existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
                        usuario.getId(), TipoCompensacion.DINERO, "Yape - Juan", 9999L))
                .isTrue();
    }

    @Test
    void detectarMetodoAsociadoAViajeConfirmado() {
        Usuario conductor = crearUsuario("met6@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("met7@upc.edu.pe", Rol.PASAJERO);
        MetodoCompensacion metodo = crearMetodo(pasajero, TipoCompensacion.DINERO, "Yape - Juan");
        MetodoCompensacion libre = crearMetodo(pasajero, TipoCompensacion.GASOLINA, "Gasolina Shell");

        Ruta ruta = crearRuta(conductor);
        Viaje confirmado = crearViaje(ruta, true);
        Viaje sinConfirmar = crearViaje(ruta, false);

        solicitudRepository.save(Solicitud.builder()
                .viaje(confirmado)
                .pasajero(pasajero)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(EstadoSolicitud.ACEPTADA)
                .metodoCompensacion(metodo)
                .build());
        solicitudRepository.save(Solicitud.builder()
                .viaje(sinConfirmar)
                .pasajero(pasajero)
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(EstadoSolicitud.ACEPTADA)
                .metodoCompensacion(metodo)
                .build());

        assertThat(metodoCompensacionRepository.estaAsociadoAViajeConfirmado(
                metodo.getId(), EstadoSolicitud.ACEPTADA)).isTrue();
        assertThat(metodoCompensacionRepository.estaAsociadoAViajeConfirmado(
                libre.getId(), EstadoSolicitud.ACEPTADA)).isFalse();
        assertThat(metodoCompensacionRepository.estaAsociadoAViajeConfirmado(
                metodo.getId(), EstadoSolicitud.PENDIENTE)).isFalse();
    }
}
