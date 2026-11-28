package com.uniride.agent.tools.conductor;

import static com.uniride.agent.conversation.Aclaraciones.aclararAmbiguedad;
import static com.uniride.agent.conversation.Aclaraciones.faltantes;
import static com.uniride.agent.conversation.Aclaraciones.filtrosDeRuta;
import static com.uniride.agent.conversation.Aclaraciones.largo;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.conversation.Aclaraciones.Discriminante;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.dto.*;
import com.uniride.agent.support.AgentQueryService;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.enums.Rol;
import com.uniride.exceptions.*;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.services.EstadisticasService;
import com.uniride.services.UsuarioService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RutaTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;
    private final EstadisticasService estadisticas;

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
        var faltantes = new ArrayList<CampoAclaracion>();
        if (blanco(origen) || largo(origen)) faltantes.add(Campo.ORIGEN.obligatorio());
        if (blanco(puntosReferencia)) faltantes.add(Campo.REFERENCIAS.obligatorio());
        if (capacidadMaxima == null || capacidadMaxima < 1) faltantes.add(Campo.CAPACIDAD.obligatorio());
        if (largo(notaContribucion)) faltantes.add(Campo.NOTA_CONTRIBUCION.obligatorio());
        if (!faltantes.isEmpty()) return faltantes("crearRuta", faltantes);
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
                    "Indica el origen o el destino actual de la ruta.")
                    .conAclaracion(Aclaraciones.alternativa("editarRuta", Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL));

        var correcciones = filtrosDeRuta(origenRuta, destinoRuta, Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL);
        if (largo(nuevoOrigen) || (nuevoOrigen != null && nuevoOrigen.isBlank()))
            correcciones.add(Campo.NUEVO_ORIGEN.obligatorio());
        if (capacidadMaxima != null && capacidadMaxima < 1) correcciones.add(Campo.CAPACIDAD.obligatorio());
        if (puntosReferencia != null && puntosReferencia.isBlank()) correcciones.add(Campo.REFERENCIAS.obligatorio());
        if (largo(notaContribucion)) correcciones.add(Campo.NOTA_CONTRIBUCION.obligatorio());
        if (!correcciones.isEmpty()) return faltantes("editarRuta", correcciones);
        if (capacidadMaxima == null && nuevoOrigen == null && puntosReferencia == null
                && blanco(zonasSinParada) && blanco(notaContribucion))
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica qué quieres cambiar de la ruta.")
                    .conAclaracion(Aclaraciones.alternativa("editarRuta", Campo.NUEVO_ORIGEN, Campo.CAPACIDAD,
                            Campo.REFERENCIAS, Campo.ZONAS_SIN_PARADA, Campo.NOTA_CONTRIBUCION));
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return rutaAmbigua("editarRuta", rutas, Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL);
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
                    "Indica el origen o el destino de la ruta que quieres eliminar.")
                    .conAclaracion(Aclaraciones.alternativa("eliminarRuta", Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL));
        var correcciones = filtrosDeRuta(origenRuta, destinoRuta, Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL);
        if (!correcciones.isEmpty()) return faltantes("eliminarRuta", correcciones);
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return rutaAmbigua("eliminarRuta", rutas, Campo.ORIGEN_ACTUAL, Campo.DESTINO_ACTUAL);
            return confirmaciones.prepararEliminacionRuta(rutas.getContent().getFirst().id());
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    static OperacionResumen rutaAmbigua(String operacion, Page<RutaRespuesta> rutas, Campo campoOrigen, Campo campoDestino) {
        var resumen = new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                "Coinciden varias rutas: " + rutas.stream().map(r -> r.origen() + " → " + r.destino())
                        .collect(Collectors.joining("; ")) + ". Indica cuál.");
        var origenes = rutas.map(RutaRespuesta::origen).getContent();
        var destinos = rutas.map(RutaRespuesta::destino).getContent();
        return aclararAmbiguedad(operacion, resumen, rutas.getTotalElements() == rutas.getNumberOfElements(),
                List.of(new Discriminante(campoOrigen, origenes, true), new Discriminante(campoDestino, destinos, true)));
    }

    private static boolean blanco(String value) { return value == null || value.isBlank(); }

    @Tool(description = """
            Consulta el ranking histórico propio de rutas por viajes completados y sus penalidades como
            conductor. Requiere rol activo CONDUCTOR y cinco viajes completados como conductor.
            No admite filtros por fechas.
            """)
    public RankingRutasRespuesta consultarRankingMisRutas() {
        if (usuarioService.usuarioActual().getRolPrincipal() != Rol.CONDUCTOR) {
            throw new NoPermitidoException("Esta consulta requiere el rol CONDUCTOR.");
        }
        return estadisticas.rankingRutas();
    }

}
