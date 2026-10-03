package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Usuario;
import com.uniride.entities.Vehiculo;
import com.uniride.enums.Rol;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class VehiculoRepositoryTest {

    @Autowired
    private VehiculoRepository vehiculoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearConductor() {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional("conductor@upc.edu.pe")
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Carlos")
                .apellidos("Ramos")
                .telefono("988777666")
                .rolPrincipal(Rol.CONDUCTOR)
                .aceptaTerminos(true)
                .build());
    }

    private Vehiculo crearVehiculo(Usuario conductor, String placa) {
        return Vehiculo.builder()
                .conductor(conductor)
                .placa(placa)
                .marca("Toyota")
                .modelo("Yaris")
                .asientos(4)
                .build();
    }

    @Test
    void guardarVehiculoYBuscarPorConductor() {
        Usuario conductor = crearConductor();
        vehiculoRepository.save(crearVehiculo(conductor, "ABC-123"));

        List<Vehiculo> vehiculos = vehiculoRepository.findByConductorId(conductor.getId());

        assertThat(vehiculos).hasSize(1);
        assertThat(vehiculos.get(0).getPlaca()).isEqualTo("ABC-123");
        assertThat(vehiculos.get(0).getAsientos()).isEqualTo(4);
    }

    @Test
    void buscarVehiculosDeConductorSinVehiculos() {
        Usuario conductor = crearConductor();

        List<Vehiculo> vehiculos = vehiculoRepository.findByConductorId(conductor.getId());

        assertThat(vehiculos).isEmpty();
    }

    @Test
    void existePlaca() {
        Usuario conductor = crearConductor();
        vehiculoRepository.save(crearVehiculo(conductor, "XYZ-987"));

        assertThat(vehiculoRepository.existsByPlaca("XYZ-987")).isTrue();
        assertThat(vehiculoRepository.existsByPlaca("QQQ-000")).isFalse();
    }
}
