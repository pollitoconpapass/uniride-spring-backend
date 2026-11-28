package com.uniride.agent.app.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.dto.requests.PerfilRequest;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.PerfilService;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerfilAgentToolsTest {

    @Mock PerfilService perfilService;
    @Mock UsuarioService usuarioService;
    @Mock ConfirmationService confirmaciones;
    private PerfilAgentTools tools;

    @BeforeEach
    void setup() {
        tools = new PerfilAgentTools(perfilService, usuarioService, confirmaciones);
    }

    @Test
    void consultarMiPerfilDevuelvePerfilCuandoExiste() {
        var perfil = perfilActual("Miraflores", null, null, null);
        when(perfilService.obtenerPerfil()).thenReturn(perfil);

        var respuesta = tools.consultarMiPerfil();

        assertThat(respuesta.completo()).isTrue();
        assertThat(respuesta.perfil()).isEqualTo(perfil);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void consultarMiPerfilInformaCuandoAunNoEstaCompleto() {
        when(perfilService.obtenerPerfil()).thenThrow(new ResourceNotFoundException("Aún no has completado tu perfil"));

        var respuesta = tools.consultarMiPerfil();

        assertThat(respuesta.completo()).isFalse();
        assertThat(respuesta.perfil()).isNull();
        assertThat(respuesta.mensaje()).contains("Aún no has completado tu perfil");
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void editarPerfilFusionaDistritoConPerfilExistenteYPreparaConfirmacion() {
        when(perfilService.obtenerPerfil()).thenReturn(perfilActual("Miraflores", "Música", "Correr", "Dato"));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma edición.");
        when(confirmaciones.prepararEditarPerfil(any(), contains("distrito → Surco"))).thenReturn(pendiente);

        var resultado = tools.editarPerfil(null, null, null, "Surco", null, null, null, null, null, null);

        assertThat(resultado).isSameAs(pendiente);
        var captor = ArgumentCaptor.forClass(PerfilRequest.class);
        verify(confirmaciones).prepararEditarPerfil(captor.capture(), contains("distrito → Surco"));
        assertThat(captor.getValue().nombre()).isEqualTo("Renzo");
        assertThat(captor.getValue().distrito()).isEqualTo("Surco");
        assertThat(captor.getValue().universidad()).isEqualTo("UPC");
    }

    @Test
    void editarPerfilDetectaSinCambiosYDatosFaltantes() {
        var sinDatos = tools.editarPerfil(null, "  ", null, null, null, null, null, null, null, null);
        assertThat(sinDatos.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);

        when(perfilService.obtenerPerfil()).thenReturn(perfilActual("Surco", null, null, null));
        var sinCambios = tools.editarPerfil(null, null, null, "Surco", null, null, null, null, null, null);
        assertThat(sinCambios.estado()).isEqualTo(EstadoOperacion.SIN_CAMBIOS);

        when(perfilService.obtenerPerfil()).thenThrow(new ResourceNotFoundException("Sin perfil"));
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build());
        var nuevoIncompleto = tools.editarPerfil(null, null, null, "Surco", null, null, null, null, null, null);
        assertThat(nuevoIncompleto.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(nuevoIncompleto.detalle()).contains("nombre", "carrera", "universidad");

        verifyNoInteractions(confirmaciones);
    }

    @Test
    void editarInfoAdicionalAgregaGustoYReemplazaCuandoSePide() {
        when(perfilService.obtenerPerfil()).thenReturn(
                perfilActual("Surco", "Escuchar jazz", "Leer", "Tengo dos gatos"));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma info.");
        when(confirmaciones.prepararEditarInfoAdicional(any(), anyString())).thenReturn(pendiente);

        var agregado = tools.editarInfoAdicional("escuchar rock", null, null, true);
        assertThat(agregado).isSameAs(pendiente);

        var captor = ArgumentCaptor.forClass(InfoAdicionalRequest.class);
        verify(confirmaciones).prepararEditarInfoAdicional(captor.capture(), contains("Escuchar jazz, escuchar rock"));
        assertThat(captor.getValue().gustos()).isEqualTo("Escuchar jazz, escuchar rock");
        assertThat(captor.getValue().hobbies()).isNull();
        assertThat(captor.getValue().datosCuriosos()).isNull();

        var duplicado = tools.editarInfoAdicional("escuchar jazz", null, null, true);
        assertThat(duplicado.estado()).isEqualTo(EstadoOperacion.SIN_CAMBIOS);
    }

    @Test
    void editarInfoAdicionalValidaPerfilYCamposObligatorios() {
        when(perfilService.obtenerPerfil()).thenReturn(perfilActual("Surco", null, null, null));

        var faltanObligatorios = tools.editarInfoAdicional("escuchar rock", null, null, true);
        assertThat(faltanObligatorios.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(faltanObligatorios.detalle()).contains("hobbies", "datos curiosos");
        verifyNoInteractions(confirmaciones);
    }

    private PerfilRespuesta perfilActual(String distrito, String gustos, String hobbies, String datos) {
        return new PerfilRespuesta(10L, "Renzo", "Vega", "Ingeniería de Software", distrito, "UPC",
                "Lunes a Viernes", "07:00 - 19:00", LocalDate.of(2026, 3, 16), LocalDate.of(2026, 12, 10),
                TipoCompensacion.GASOLINA, gustos, hobbies, datos, LocalDateTime.now());
    }
}
