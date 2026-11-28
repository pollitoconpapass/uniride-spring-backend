package com.uniride.agent.tools;

import com.uniride.agent.tools.shared.PerfilTools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.dto.requests.CambiarRolRequest;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.services.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsuarioAgentToolsTest {

    @Mock UsuarioService usuarioService;
    @Mock ConfirmationService confirmaciones;
    private PerfilTools tools;

    @BeforeEach
    void setup() {
        tools = new PerfilTools(mock(com.uniride.services.PerfilService.class), usuarioService, confirmaciones);
    }

    @Test
    void consultarMiUsuarioDevuelveDatosDeCuentaSinCredenciales() {
        var respuesta = new UsuarioRespuesta(5L, "renzo@upc.edu.pe", "Renzo", "Vega", "999888777",
                Rol.PASAJERO, true);
        when(usuarioService.obtenerUsuarioActual()).thenReturn(respuesta);

        assertThat(tools.consultarMiUsuario()).isEqualTo(respuesta);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void cambiarRolPreparaConfirmacionSoloCuandoCambiaElRol() {
        assertThat(tools.cambiarRol(null).estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);

        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(5L).rolPrincipal(Rol.PASAJERO).build());
        assertThat(tools.cambiarRol(Rol.PASAJERO).estado()).isEqualTo(EstadoOperacion.SIN_CAMBIOS);
        verifyNoInteractions(confirmaciones);

        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma rol.");
        when(confirmaciones.prepararCambiarRol(new CambiarRolRequest(Rol.CONDUCTOR))).thenReturn(pendiente);

        assertThat(tools.cambiarRol(Rol.CONDUCTOR)).isSameAs(pendiente);
        verify(confirmaciones).prepararCambiarRol(new CambiarRolRequest(Rol.CONDUCTOR));
    }
}
