package com.uniride.agent.tools;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.enums.Rol;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.util.List;
import java.util.stream.Collectors;
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

    @Tool(description = """
            Prepara la edición de una ruta propia. NO modifica todavía la ruta.
            Identifica la ruta por el origen y, si lo indicó, el destino actuales.
            Envía solo los campos que el usuario pidió cambiar; los demás conservan su valor.
            El cambio se aplica al confirmar el resumen.
            """)
    public OperacionResumen editarRuta(
            @ToolParam(description = "Origen actual de la ruta", required = false) String origenRuta,
            @ToolParam(description = "Destino actual, si lo indicó el usuario", required = false) String destinoRuta,
            @ToolParam(description = "Nueva capacidad máxima", required = false) Integer capacidadMaxima,
            @ToolParam(description = "Nuevo origen", required = false) String nuevoOrigen,
            @ToolParam(description = "Nuevos puntos de referencia", required = false) String puntosReferencia,
            @ToolParam(description = "Nuevas zonas sin parada", required = false) String zonasSinParada,
            @ToolParam(description = "Nueva nota de contribución", required = false) String notaContribucion) {
        if (usuarioService.usuarioActual().getRolPrincipal() != Rol.CONDUCTOR)
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede editar rutas.");
        if (blanco(origenRuta) && blanco(destinoRuta))
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el origen o el destino actual de la ruta.");
        if (capacidadMaxima == null && blanco(nuevoOrigen) && blanco(puntosReferencia)
                && blanco(zonasSinParada) && blanco(notaContribucion))
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica qué quieres cambiar de la ruta.");
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varias rutas: " + rutas.stream().map(r -> r.origen() + " → " + r.destino())
                                .collect(Collectors.joining("; ")) + ". Indica cuál.");
            return confirmaciones.prepararEdicionRuta(rutas.getContent().getFirst().id(), nuevoOrigen,
                    capacidadMaxima, puntosReferencia, zonasSinParada, notaContribucion);
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara eliminar una ruta propia. NO la elimina todavía.
            Identifica la ruta por origen y, si lo indicó, destino actuales.
            Los viajes programados futuros se cancelarán y se avisará a los pasajeros.
            La eliminación se aplica al confirmar el resumen.
            """)
    public OperacionResumen eliminarRuta(
            @ToolParam(description = "Origen actual de la ruta", required = false) String origenRuta,
            @ToolParam(description = "Destino actual, si lo indicó el usuario", required = false) String destinoRuta) {
        if (usuarioService.usuarioActual().getRolPrincipal() != Rol.CONDUCTOR)
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede eliminar rutas.");
        if (blanco(origenRuta) && blanco(destinoRuta))
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el origen o el destino de la ruta que quieres eliminar.");
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varias rutas: " + rutas.stream().map(r -> r.origen() + " → " + r.destino())
                                .collect(Collectors.joining("; ")) + ". Indica cuál.");
            return confirmaciones.prepararEliminacionRuta(rutas.getContent().getFirst().id());
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private boolean blanco(String value) { return value == null || value.isBlank(); }
}
