package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class ViajeRepositoryTest {

    @Autowired
    private ViajeRepository viajeRepository;

    @Autowired
    private RutaRepository rutaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario(String correo) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Diego")
                .apellidos("Torres")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.CONDUCTOR)
                .aceptaTerminos(true)
                .build());
    }

    private Ruta crearRuta(Usuario conductor, String origen) {
        return rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen(origen)
                .destino("UPC")
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa")
                .estado(EstadoRuta.ACTIVA)
                .build());
    }

    private Viaje crearViaje(Ruta ruta, LocalDate fecha, LocalTime hora) {
        return Viaje.builder()
                .ruta(ruta)
                .fecha(fecha)
                .hora(hora)
                .dia("Lunes")
                .build();
    }

    @Test
    void crearViajeConEstadoPorDefecto() {
        Usuario conductor = crearUsuario("viaje1@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");

        Viaje guardado = viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));

        assertThat(guardado.getEstado()).isEqualTo(EstadoViaje.PROGRAMADO);
        assertThat(guardado.isConfirmado()).isFalse();
    }

    @Test
    void buscarViajesDeUnaRutaOrdenadosPorFecha() {
        Usuario conductor = crearUsuario("viaje2@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");
        Ruta otraRuta = crearRuta(conductor, "Surco");

        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 7), LocalTime.of(7, 30)));
        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));
        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 6), LocalTime.of(18, 0)));
        viajeRepository.save(crearViaje(otraRuta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));

        Page<Viaje> viajes = viajeRepository.findByRutaIdOrderByFechaAscHoraAsc(ruta.getId(),
                PageRequest.of(0, 10));

        assertThat(viajes.getContent()).hasSize(3);
        assertThat(viajes.getContent().get(0).getFecha()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(viajes.getContent().get(1).getFecha()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(viajes.getContent().get(2).getFecha()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void buscarViajePorIdYConductorDeLaRuta() {
        Usuario conductor = crearUsuario("viaje3@upc.edu.pe");
        Usuario otro = crearUsuario("viaje4@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");
        Viaje viaje = viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));

        assertThat(viajeRepository.findByIdAndRutaConductorId(viaje.getId(), conductor.getId())).isPresent();
        assertThat(viajeRepository.findByIdAndRutaConductorId(viaje.getId(), otro.getId())).isEmpty();
    }

    @Test
    void rutaSinViajes() {
        Usuario conductor = crearUsuario("viaje5@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");

        assertThat(viajeRepository.existsByRutaId(ruta.getId())).isFalse();
        assertThat(viajeRepository.findByRutaIdOrderByFechaAscHoraAsc(ruta.getId(),
                PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void buscarViajesSinFiltrosDevuelveSoloFuturosProgramados() {
        Usuario conductor = crearUsuario("viaje6@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");

        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));
        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 6), LocalTime.of(18, 0)));
        viajeRepository.save(crearViaje(ruta, LocalDate.of(2020, 1, 1), LocalTime.of(7, 30)));
        viajeRepository.save(Viaje.builder()
                .ruta(ruta)
                .fecha(LocalDate.of(2026, 10, 7))
                .hora(LocalTime.of(8, 0))
                .dia("Lunes")
                .estado(EstadoViaje.CANCELADO)
                .build());

        Page<Viaje> resultados = viajeRepository.buscarViajes(null, null, null, null,
                null, EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));

        assertThat(resultados.getContent()).hasSize(2);
    }

    @Test
    void buscarViajesPorOrigenDestinoDiaYHora() {
        Usuario conductor = crearUsuario("viaje7@upc.edu.pe");
        Ruta rutaSanBorja = crearRuta(conductor, "San Borja");
        Ruta rutaSurco = crearRuta(conductor, "Surco");

        viajeRepository.save(crearViaje(rutaSanBorja, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));
        viajeRepository.save(crearViaje(rutaSurco, LocalDate.of(2026, 10, 6), LocalTime.of(18, 0)));

        Page<Viaje> porOrigen = viajeRepository.buscarViajes("san borja", "upc", "lunes", null,
                null, EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));
        Page<Viaje> porHora = viajeRepository.buscarViajes(null, null, null, LocalTime.of(18, 0),
                null, EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));
        Page<Viaje> porDiaSinResultados = viajeRepository.buscarViajes(null, null, "martes", null,
                null, EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));

        assertThat(porOrigen.getContent()).hasSize(1);
        assertThat(porOrigen.getContent().get(0).getRuta().getOrigen()).isEqualTo("San Borja");
        assertThat(porHora.getContent()).hasSize(1);
        assertThat(porDiaSinResultados.getContent()).isEmpty();
    }

    @Test
    void buscarViajesPorCercania() {
        Usuario conductor = crearUsuario("viaje8@upc.edu.pe");
        Ruta ruta = crearRuta(conductor, "San Borja");
        viajeRepository.save(crearViaje(ruta, LocalDate.of(2026, 10, 5), LocalTime.of(7, 30)));

        Page<Viaje> cercanos = viajeRepository.buscarViajes(null, null, null, null,
                "%san borja%", EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));
        Page<Viaje> lejanos = viajeRepository.buscarViajes(null, null, null, null,
                "%surco%", EstadoViaje.PROGRAMADO, PageRequest.of(0, 10));

        assertThat(cercanos.getContent()).hasSize(1);
        assertThat(lejanos.getContent()).isEmpty();
    }
}
