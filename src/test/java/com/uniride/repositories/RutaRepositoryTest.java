package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.Rol;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest
class RutaRepositoryTest {

    @Autowired
    private RutaRepository rutaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario(String correo) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Carlos")
                .apellidos("Ramirez")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.CONDUCTOR)
                .aceptaTerminos(true)
                .build());
    }

    private Ruta crearRuta(Usuario conductor, String origen, String destino, EstadoRuta estado) {
        return Ruta.builder()
                .conductor(conductor)
                .origen(origen)
                .destino(destino)
                .capacidadMaxima(3)
                .puntosReferencia("Av. Arequipa, Av. Brasil")
                .zonasSinParada("SJM, Lince")
                .notaContribucion("Gasolina")
                .estado(estado)
                .build();
    }

    @Test
    void crearRutaConEstadoPorDefecto() {
        Usuario conductor = crearUsuario("ruta1@upc.edu.pe");

        Ruta guardada = rutaRepository.save(Ruta.builder()
                .conductor(conductor)
                .origen("San Borja")
                .destino("UPC")
                .capacidadMaxima(2)
                .puntosReferencia("Av. Aviación")
                .build());

        assertThat(guardada.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
        assertThat(guardada.getFechaCreacion()).isNotNull();
    }

    @Test
    void buscarRutasPorConductorOrdenadas() {
        Usuario conductor = crearUsuario("ruta2@upc.edu.pe");
        Usuario otro = crearUsuario("ruta3@upc.edu.pe");

        Ruta primera = crearRuta(conductor, "Surco", "PUCP", EstadoRuta.ACTIVA);
        primera.setFechaCreacion(LocalDateTime.of(2026, 9, 1, 8, 0));
        rutaRepository.save(primera);

        Ruta ultima = crearRuta(conductor, "San Isidro", "UNI", EstadoRuta.ACTIVA);
        ultima.setFechaCreacion(LocalDateTime.of(2026, 9, 10, 8, 0));
        rutaRepository.save(ultima);

        Ruta tercera = crearRuta(conductor, "Miraflores", "UTEC", EstadoRuta.VENCIDA);
        tercera.setFechaCreacion(LocalDateTime.of(2026, 9, 5, 8, 0));
        rutaRepository.save(tercera);
        rutaRepository.save(crearRuta(otro, "Lima", "Callao", EstadoRuta.ACTIVA));

        Page<Ruta> rutas = rutaRepository.findByConductorId(conductor.getId(),
                PageRequest.of(0, 10, Sort.by("fechaCreacion").descending()));

        assertThat(rutas.getContent()).hasSize(3);
        assertThat(rutas.getContent().get(0).getOrigen()).isEqualTo("San Isidro");
        assertThat(rutas.getContent().get(2).getOrigen()).isEqualTo("Surco");
    }

    @Test
    void buscarRutasPorConductorYEstadoConJpql() {
        Usuario conductor = crearUsuario("ruta4@upc.edu.pe");
        Usuario otro = crearUsuario("ruta5@upc.edu.pe");

        rutaRepository.save(crearRuta(conductor, "San Borja", "UTEC", EstadoRuta.ACTIVA));
        rutaRepository.save(crearRuta(conductor, "Surco", "PUCP", EstadoRuta.ACTIVA));
        rutaRepository.save(crearRuta(conductor, "La Molina", "UNI", EstadoRuta.VENCIDA));
        rutaRepository.save(crearRuta(otro, "Lima", "Callao", EstadoRuta.ACTIVA));

        Page<Ruta> activas = rutaRepository.buscarPorConductorYEstado(conductor.getId(),
                EstadoRuta.ACTIVA, PageRequest.of(0, 10));
        Page<Ruta> vencidas = rutaRepository.buscarPorConductorYEstado(conductor.getId(),
                EstadoRuta.VENCIDA, PageRequest.of(0, 10));
        Page<Ruta> llenas = rutaRepository.buscarPorConductorYEstado(conductor.getId(),
                EstadoRuta.LLENA, PageRequest.of(0, 10));

        assertThat(activas.getContent()).hasSize(2);
        assertThat(vencidas.getContent()).hasSize(1);
        assertThat(llenas.getContent()).isEmpty();
    }

    @Test
    void contarRutasPorConductorYEstadoConSqlNativo() {
        Usuario conductor = crearUsuario("ruta6@upc.edu.pe");
        Usuario otro = crearUsuario("ruta7@upc.edu.pe");

        rutaRepository.save(crearRuta(conductor, "San Borja", "UTEC", EstadoRuta.ACTIVA));
        rutaRepository.save(crearRuta(conductor, "Surco", "PUCP", EstadoRuta.ACTIVA));
        rutaRepository.save(crearRuta(conductor, "La Molina", "UNI", EstadoRuta.VENCIDA));
        rutaRepository.save(crearRuta(otro, "Lima", "Callao", EstadoRuta.ACTIVA));

        long activas = rutaRepository.contarPorConductorYEstado(conductor.getId(), "ACTIVA");
        long vencidas = rutaRepository.contarPorConductorYEstado(conductor.getId(), "VENCIDA");
        long llenas = rutaRepository.contarPorConductorYEstado(conductor.getId(), "LLENA");
        long activasOtro = rutaRepository.contarPorConductorYEstado(otro.getId(), "ACTIVA");

        assertThat(activas).isEqualTo(2);
        assertThat(vencidas).isEqualTo(1);
        assertThat(llenas).isZero();
        assertThat(activasOtro).isEqualTo(1);
    }

    @Test
    void buscarRutaPorIdYConductor() {
        Usuario conductor = crearUsuario("ruta8@upc.edu.pe");
        Usuario otro = crearUsuario("ruta9@upc.edu.pe");
        Ruta ruta = rutaRepository.save(crearRuta(conductor, "Surco", "PUCP", EstadoRuta.ACTIVA));

        assertThat(rutaRepository.findByIdAndConductorId(ruta.getId(), conductor.getId())).isPresent();
        assertThat(rutaRepository.findByIdAndConductorId(ruta.getId(), otro.getId())).isEmpty();
    }

    @Test
    void conductorSinRutasPublicadas() {
        Usuario conductor = crearUsuario("ruta10@upc.edu.pe");

        assertThat(rutaRepository.existsByConductorId(conductor.getId())).isFalse();
        assertThat(rutaRepository.findByConductorId(conductor.getId(),
                PageRequest.of(0, 10)).getContent()).isEmpty();
    }
}
