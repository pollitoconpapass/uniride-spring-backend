package com.uniride.agent.tools;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.dto.requests.CambiarRolRequest;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UsuarioAgentTools {

    private final UsuarioService usuarioService;
    private final ConfirmationService confirmaciones;

    @Tool(description = """
            Consulta los datos de la cuenta del usuario autenticado (nombre, apellidos, correo institucional,
            teléfono, rol activo y estado de verificación).
            """)
    public UsuarioRespuesta consultarMiUsuario() {
        return usuarioService.obtenerUsuarioActual();
    }

    @Tool(description = """
            Prepara el cambio del rol activo del usuario entre CONDUCTOR y PASAJERO.
            NO cambia el rol todavía; devuelve una propuesta que requiere confirmación del usuario.
            """)
    public OperacionResumen cambiarRol(
            @ToolParam(description = "Nuevo rol activo: CONDUCTOR o PASAJERO", required = false) Rol rol) {
        if (rol == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica si deseas cambiar tu rol activo a CONDUCTOR o PASAJERO.");
        }
        Usuario actual = usuarioService.usuarioActual();
        if (actual.getRolPrincipal() == rol) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, actual.getId(),
                    "Tu rol activo ya es " + rol + ".");
        }
        try {
            return confirmaciones.prepararCambiarRol(new CambiarRolRequest(rol));
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }
}
