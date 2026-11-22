package com.uniride.agent.tools;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.enums.Rol;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RutaAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;

    @Tool(description = """
            Consulta rutas propias del conductor, sin las eliminadas. Devuelve diez por página.
            pagina comienza en cero; hayMas indica si debes ofrecer la siguiente página.
            El estado es el persistido; esta consulta nunca modifica datos.
            """)
    public PaginaAgente<RutaResumen> consultarMisRutas(
            @ToolParam(description = "Página desde cero; omitir para la primera", required = false) Integer pagina) {
        return consultas.rutas(pagina);
    }

    /** Compatibility for Java callers; only the paginated overload is exposed to the model. */
    public List<RutaResumen> consultarMisRutas() { return consultarMisRutas(null).items(); }

    @Tool(description = """
            Prepara una ruta propia para confirmación. NO publica ni modifica datos de negocio.
            El destino es la universidad del perfil. Devuelve confirmacion con resumen e identificador.
            La propuesta requiere una decisión explícita del usuario procesada por el servidor.
            """)
    public OperacionResumen crearRuta(
            @ToolParam(description = "Punto de salida", required = false) String origen,
            @ToolParam(description = "Capacidad máxima de pasajeros", required = false) Integer capacidadMaxima,
            @ToolParam(description = "Puntos de referencia", required = false) String puntosReferencia,
            @ToolParam(description = "Zonas sin parada, si las indicó el usuario", required = false) String zonasSinParada,
            @ToolParam(description = "Nota de contribución, si la indicó el usuario", required = false) String notaContribucion) {
        if (usuarioService.usuarioActual().getRolPrincipal() != Rol.CONDUCTOR)
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede publicar rutas.");
        if (blanco(origen) || blanco(puntosReferencia) || capacidadMaxima == null || capacidadMaxima < 1)
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Completa el origen, los puntos de referencia y una capacidad mayor a cero.");
        try {
            return confirmaciones.prepararRuta(new RutaRequest(origen, capacidadMaxima,
                    puntosReferencia, zonasSinParada, notaContribucion));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private boolean blanco(String value) { return value == null || value.isBlank(); }
}
