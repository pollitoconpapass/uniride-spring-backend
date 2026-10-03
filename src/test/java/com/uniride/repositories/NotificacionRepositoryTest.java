package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Notificacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoNotificacion;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class NotificacionRepositoryTest {

    @Autowired
    private NotificacionRepository notificacionRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario crearUsuario() {
        return usuarioRepository.save(Usuario.builder()
                .correoInstitucional("usuario@upc.edu.pe")
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Maria")
                .apellidos("Lopez")
                .telefono("966555444")
                .rolPrincipal(Rol.PASAJERO)
                .aceptaTerminos(true)
                .build());
    }

    private Notificacion crearNotificacion(Usuario usuario, TipoNotificacion tipo, String mensaje,
            LocalDateTime fechaEnvio, boolean leida) {
        return Notificacion.builder()
                .usuario(usuario)
                .tipo(tipo)
                .mensaje(mensaje)
                .leida(leida)
                .fechaEnvio(fechaEnvio)
                .build();
    }

    @Test
    void guardarNotificacion() {
        Usuario usuario = crearUsuario();

        Notificacion guardada = notificacionRepository.save(crearNotificacion(usuario,
                TipoNotificacion.EXITO, "Ruta publicada correctamente", LocalDateTime.now(), false));

        assertThat(guardada.getId()).isNotNull();
        assertThat(guardada.getFechaEnvio()).isNotNull();
        assertThat(guardada.isLeida()).isFalse();
    }

    @Test
    void buscarNotificacionesPorUsuarioOrdenadasPorFechaDesc() {
        Usuario usuario = crearUsuario();
        notificacionRepository.save(crearNotificacion(usuario, TipoNotificacion.EXITO,
                "Primera", LocalDateTime.now().minusDays(2), true));
        notificacionRepository.save(crearNotificacion(usuario, TipoNotificacion.ERROR,
                "Segunda", LocalDateTime.now(), false));

        List<Notificacion> notificaciones =
                notificacionRepository.findByUsuarioIdOrderByFechaEnvioDesc(usuario.getId());

        assertThat(notificaciones).hasSize(2);
        assertThat(notificaciones.get(0).getMensaje()).isEqualTo("Segunda");
        assertThat(notificaciones.get(1).getMensaje()).isEqualTo("Primera");
    }

    @Test
    void contarNotificacionesNoLeidas() {
        Usuario usuario = crearUsuario();
        notificacionRepository.save(crearNotificacion(usuario, TipoNotificacion.EXITO,
                "A", LocalDateTime.now().minusHours(3), true));
        notificacionRepository.save(crearNotificacion(usuario, TipoNotificacion.ADVERTENCIA,
                "B", LocalDateTime.now().minusHours(2), false));
        notificacionRepository.save(crearNotificacion(usuario, TipoNotificacion.RECORDATORIO,
                "C", LocalDateTime.now().minusHours(1), false));

        long noLeidas = notificacionRepository.countByUsuarioIdAndLeidaFalse(usuario.getId());

        assertThat(noLeidas).isEqualTo(2);
    }
}
