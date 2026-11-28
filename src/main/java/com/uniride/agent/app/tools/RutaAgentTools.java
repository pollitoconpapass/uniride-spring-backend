package com.uniride.agent.app.tools;

import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.clarification.Aclaraciones.Campo;
import com.uniride.agent.app.clarification.Aclaraciones;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OpcionAclaracion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.agent.app.dto.PaginaAgente;
import com.uniride.agent.app.dto.RutaResumen;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.enums.Rol;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RutaAgentTools {
    static final int LONGITUD_FILTRO = 255;
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

    static OperacionResumen faltantes(String operacion, List<CampoAclaracion> campos) {
        String descripcion = campos.stream().map(CampoAclaracion::etiqueta)
                .map(etiqueta -> etiqueta.toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica " + descripcion + ".")
                .conAclaracion(Aclaraciones.crear(operacion, campos.toArray(CampoAclaracion[]::new)));
    }

    static List<CampoAclaracion> filtrosDeRuta(String origen, String destino, Campo campoOrigen, Campo campoDestino) {
        var correcciones = new ArrayList<CampoAclaracion>();
        if (largo(origen)) correcciones.add(campoOrigen.obligatorio());
        if (largo(destino)) correcciones.add(campoDestino.obligatorio());
        return correcciones;
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

    record Discriminante(Campo campo, List<String> valores, boolean parcial) { }

    static OperacionResumen aclararAmbiguedad(String operacion, OperacionResumen resumen, boolean todosVisibles,
                                              List<Discriminante> posibles) {
        var discriminantes = posibles.stream().filter(RutaAgentTools::diferentes).toList();
        if (todosVisibles) {
            for (var criterio : discriminantes) {
                var opciones = opcionesUnicas(criterio);
                if (opciones.isPresent())
                    return resumen.conAclaracion(Aclaraciones.crear(operacion, opciones.get()).conOpcionesRapidas());
            }
        }
        if (discriminantes.isEmpty()) return resumen;
        if (discriminantes.size() == 1)
            return resumen.conAclaracion(Aclaraciones.crear(operacion, discriminantes.getFirst().campo().obligatorio()));
        return resumen.conAclaracion(Aclaraciones.alternativa(operacion,
                discriminantes.stream().map(Discriminante::campo).toArray(Campo[]::new)));
    }

    private static boolean diferentes(Discriminante criterio) {
        return criterio.valores().stream().filter(Objects::nonNull)
                .map(RutaAgentTools::normalizar).filter(valor -> !valor.isEmpty()).distinct().limit(2).count() == 2;
    }

    private static Optional<CampoAclaracion> opcionesUnicas(Discriminante criterio) {
        var valores = criterio.valores();
        if (valores.size() > 6 || valores.stream().anyMatch(valor -> blanco(valor)
                || (criterio.parcial() && (valor.contains("%") || valor.contains("_"))))) return Optional.empty();
        var normalizados = valores.stream().map(RutaAgentTools::normalizar).toList();
        boolean unicos = normalizados.stream().allMatch(valor -> normalizados.stream()
                .filter(otro -> criterio.parcial() ? otro.contains(valor) : otro.equals(valor)).limit(2).count() == 1);
        if (!unicos) return Optional.empty();
        try {
            return Optional.of(criterio.campo().conOpciones(valores.stream()
                    .map(valor -> new OpcionAclaracion(valor.strip(), valor.strip())).toList()));
        } catch (IllegalArgumentException valorNoPublicable) {
            return Optional.empty();
        }
    }

    private static String normalizar(String valor) { return valor.strip().toLowerCase(Locale.ROOT); }

    private static boolean largo(String valor) { return valor != null && valor.length() > LONGITUD_FILTRO; }

    private static boolean blanco(String value) { return value == null || value.isBlank(); }
}
