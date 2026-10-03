package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoPenalidad;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PenalidadRepositoryTest {

    @Autowired
    private PenalidadRepository penalidadRepository;

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
                .nombre("Rosa")
                .apellidos("Torres")
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
                .estado(EstadoViaje.PROGRAMADO)
                .build());
    }

    @Test
    void crearPenalidadConFechaPorDefecto() {
        Usuario conductor = crearUsuario("pen1@upc.edu.pe", Rol.CONDUCTOR);

        Penalidad penalidad = penalidadRepository.save(Penalidad.builder()
                .usuario(conductor)
                .tipo(TipoPenalidad.LEVE)
                .motivo("Cancelación tardía")
                .build());

        assertThat(penalidad.getFecha()).isNotNull();
        assertThat(penalidad.getViaje()).isNull();
    }

    @Test
    void contarPenalidadesPorUsuario() {
        Usuario conductor = crearUsuario("pen2@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        penalidadRepository.save(Penalidad.builder()
                .usuario(conductor).viaje(viaje).tipo(TipoPenalidad.LEVE)
                .motivo("No confirmó a tiempo").build());
        penalidadRepository.save(Penalidad.builder()
                .usuario(conductor).tipo(TipoPenalidad.GRAVE)
                .motivo("Cancelación con menos de 4 horas").build());

        assertThat(penalidadRepository.countByUsuarioId(conductor.getId())).isEqualTo(2);
        assertThat(penalidadRepository.countByUsuarioId(9999L)).isZero();
    }

    @Test
    void detectarPenalidadDuplicadaPorViaje() {
        Usuario conductor = crearUsuario("pen3@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        penalidadRepository.save(Penalidad.builder()
                .usuario(conductor).viaje(viaje).tipo(TipoPenalidad.LEVE)
                .motivo("No confirmó a tiempo").build());

        assertThat(penalidadRepository.existsByViajeIdAndUsuarioId(
                viaje.getId(), conductor.getId())).isTrue();
        assertThat(penalidadRepository.existsByViajeIdAndUsuarioId(
                viaje.getId(), 9999L)).isFalse();
        assertThat(penalidadRepository.findByViajeId(viaje.getId())).hasSize(1);
    }

    @Test
    void contarPenalidadesSoloLasAsociadasAViaje() {
        Usuario conductor = crearUsuario("pen4@upc.edu.pe", Rol.CONDUCTOR);
        Viaje viaje = crearViaje(conductor);

        penalidadRepository.save(Penalidad.builder()
                .usuario(conductor).viaje(viaje).tipo(TipoPenalidad.LEVE)
                .motivo("Cancelación con 10 horas").build());
        penalidadRepository.save(Penalidad.builder()
                .usuario(conductor).tipo(TipoPenalidad.GRAVE)
                .motivo("Incumplimiento general").build());

        assertThat(penalidadRepository.countByUsuarioId(conductor.getId())).isEqualTo(2);
        assertThat(penalidadRepository.countByUsuarioIdAndViajeIsNotNull(conductor.getId())).isEqualTo(1);
    }
}
