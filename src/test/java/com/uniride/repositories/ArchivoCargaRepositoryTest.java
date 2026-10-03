package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.ArchivoCarga;
import com.uniride.entities.HorarioAcademico;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class ArchivoCargaRepositoryTest {

    @Autowired
    private ArchivoCargaRepository archivoCargaRepository;

    @Autowired
    private HorarioAcademicoRepository horarioAcademicoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private HorarioAcademico crearHorario(String correo) {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Carlos")
                .apellidos("Ramos")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.CONDUCTOR)
                .aceptaTerminos(true)
                .build());
        return horarioAcademicoRepository.save(HorarioAcademico.builder().usuario(usuario).build());
    }

    private ArchivoCarga crearArchivo(HorarioAcademico horario, String nombre, String formato) {
        return ArchivoCarga.builder()
                .horarioAcademico(horario)
                .nombre(nombre)
                .formato(formato)
                .tamanoMb(0.5)
                .build();
    }

    @Test
    void guardarYBuscarArchivosPorHorario() {
        HorarioAcademico horario = crearHorario("archivo1@upc.edu.pe");
        archivoCargaRepository.save(crearArchivo(horario, "horarios.txt", "txt"));

        List<ArchivoCarga> archivos =
                archivoCargaRepository.findByHorarioAcademicoIdOrderByFechaSubidaDesc(horario.getId());

        assertThat(archivos).hasSize(1);
        assertThat(archivos.get(0).getNombre()).isEqualTo("horarios.txt");
        assertThat(archivos.get(0).getTamanoMb()).isEqualTo(0.5);
        assertThat(archivos.get(0).getFechaSubida()).isNotNull();
    }

    @Test
    void contarArchivosPorHorario() {
        HorarioAcademico horario = crearHorario("archivo2@upc.edu.pe");
        archivoCargaRepository.save(crearArchivo(horario, "a.txt", "txt"));
        archivoCargaRepository.save(crearArchivo(horario, "b.pdf", "pdf"));

        long total = archivoCargaRepository.countByHorarioAcademicoId(horario.getId());

        assertThat(total).isEqualTo(2);
    }

    @Test
    void buscarArchivosDeHorarioSinArchivos() {
        HorarioAcademico horario = crearHorario("archivo3@upc.edu.pe");

        List<ArchivoCarga> archivos =
                archivoCargaRepository.findByHorarioAcademicoIdOrderByFechaSubidaDesc(horario.getId());

        assertThat(archivos).isEmpty();
    }
}
