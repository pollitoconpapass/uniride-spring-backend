package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PerfilRepositoryTest {

    @Autowired
    private PerfilRepository perfilRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario(String correo) {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Ana")
                .apellidos("Garcia")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.PASAJERO)
                .aceptaTerminos(true)
                .build());
    }

    private Perfil crearPerfil(Usuario usuario, String distrito) {
        return Perfil.builder()
                .usuario(usuario)
                .nombre("Ana Garcia")
                .carrera("Ingenieria de Sistemas")
                .distrito(distrito)
                .universidad("UPC")
                .diasDisponibles("Lunes,Miercoles")
                .horariosDisponibles("08:00-18:00")
                .fechaInicioClases(LocalDate.of(2026, 3, 2))
                .fechaFinClases(LocalDate.of(2026, 7, 10))
                .metodoCompensacionFavorito(TipoCompensacion.GASOLINA)
                .build();
    }

    @Test
    void guardarYBuscarPerfilPorUsuario() {
        Usuario usuario = crearUsuario("perfil1@upc.edu.pe");
        perfilRepository.save(crearPerfil(usuario, "Surco"));

        Optional<Perfil> encontrado = perfilRepository.findByUsuarioId(usuario.getId());

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getCarrera()).isEqualTo("Ingenieria de Sistemas");
        assertThat(encontrado.get().getFechaActualizacion()).isNotNull();
    }

    @Test
    void existePerfilPorUsuario() {
        Usuario usuario = crearUsuario("perfil2@upc.edu.pe");

        assertThat(perfilRepository.existsByUsuarioId(usuario.getId())).isFalse();

        perfilRepository.save(crearPerfil(usuario, "San Isidro"));

        assertThat(perfilRepository.existsByUsuarioId(usuario.getId())).isTrue();
    }

    @Test
    void buscarPerfilPorUsuarioInexistente() {
        Optional<Perfil> encontrado = perfilRepository.findByUsuarioId(99999L);

        assertThat(encontrado).isEmpty();
    }

    @Test
    void buscarPerfilesPorDistritoConJpql() {
        Usuario usuario1 = crearUsuario("perfil3@upc.edu.pe");
        Usuario usuario2 = crearUsuario("perfil4@upc.edu.pe");
        perfilRepository.save(crearPerfil(usuario1, "Miraflores"));
        perfilRepository.save(crearPerfil(usuario2, "Miraflores"));
        perfilRepository.save(crearPerfil(crearUsuario("perfil5@upc.edu.pe"), "Lima"));

        List<Perfil> miraflores = perfilRepository.buscarPorDistrito("Miraflores");

        assertThat(miraflores).hasSize(2);
    }

    @Test
    void actualizarDatosDelPerfil() {
        Usuario usuario = crearUsuario("perfil6@upc.edu.pe");
        Perfil perfil = perfilRepository.save(crearPerfil(usuario, "Surco"));

        perfil.setDistrito("La Molina");
        perfil.setMetodoCompensacionFavorito(TipoCompensacion.DINERO);
        perfilRepository.save(perfil);

        Perfil actualizado = perfilRepository.findByUsuarioId(usuario.getId()).orElseThrow();
        assertThat(actualizado.getDistrito()).isEqualTo("La Molina");
        assertThat(actualizado.getMetodoCompensacionFavorito()).isEqualTo(TipoCompensacion.DINERO);
    }
}
