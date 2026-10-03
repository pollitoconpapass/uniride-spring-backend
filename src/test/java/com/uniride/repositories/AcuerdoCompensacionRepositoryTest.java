package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.AcuerdoCompensacion;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoAcuerdo;
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
class AcuerdoCompensacionRepositoryTest {

    @Autowired
    private AcuerdoCompensacionRepository acuerdoCompensacionRepository;

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
                .nombre("Diego")
                .apellidos("Ramirez")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(rol)
                .aceptaTerminos(true)
                .build());
    }

    private Solicitud crearSolicitudAceptada(Usuario conductor, Usuario pasajero) {
        Ruta ruta = rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen("San Borja")
                .destino("UPC")
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa")
                .estado(EstadoRuta.ACTIVA)
                .build());
        Viaje viaje = viajeRepository.save(Viaje.builder()
                .ruta(ruta)
                .fecha(LocalDate.of(2026, 10, 5))
                .hora(LocalTime.of(7, 30))
                .dia("Lunes")
                .build());
        return solicitudRepository.save(Solicitud.builder()
                .viaje(viaje)
                .pasajero(pasajero)
                .mensaje("Me interesa")
                .preferenciaCompensacion(TipoCompensacion.DINERO)
                .estado(EstadoSolicitud.ACEPTADA)
                .build());
    }

    @Test
    void crearAcuerdoConEstadoPorDefecto() {
        Usuario conductor = crearUsuario("acu1@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("acu2@upc.edu.pe", Rol.PASAJERO);
        Solicitud solicitud = crearSolicitudAceptada(conductor, pasajero);

        AcuerdoCompensacion acuerdo = acuerdoCompensacionRepository.save(AcuerdoCompensacion.builder()
                .solicitud(solicitud)
                .terminos("Compensación en dinero por el viaje")
                .build());

        assertThat(acuerdo.getEstado()).isEqualTo(EstadoAcuerdo.BORRADOR);
        assertThat(acuerdo.getFechaCreacion()).isNotNull();
    }

    @Test
    void buscarAcuerdoPorSolicitud() {
        Usuario conductor = crearUsuario("acu3@upc.edu.pe", Rol.CONDUCTOR);
        Usuario pasajero = crearUsuario("acu4@upc.edu.pe", Rol.PASAJERO);
        Solicitud solicitud = crearSolicitudAceptada(conductor, pasajero);

        AcuerdoCompensacion guardado = acuerdoCompensacionRepository.save(AcuerdoCompensacion.builder()
                .solicitud(solicitud)
                .terminos("Compensación en gasolina")
                .estado(EstadoAcuerdo.BORRADOR)
                .build());

        assertThat(acuerdoCompensacionRepository.findBySolicitudId(solicitud.getId())).isPresent();
        AcuerdoCompensacion encontrado = acuerdoCompensacionRepository
                .findBySolicitudId(solicitud.getId()).orElseThrow();
        assertThat(encontrado.getId()).isEqualTo(guardado.getId());
        assertThat(encontrado.getTerminos()).isEqualTo("Compensación en gasolina");
        assertThat(acuerdoCompensacionRepository.findBySolicitudId(9999L)).isEmpty();
    }
}
