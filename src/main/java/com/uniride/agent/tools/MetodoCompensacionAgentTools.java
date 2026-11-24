package com.uniride.agent.tools;

import static com.uniride.agent.Aclaraciones.Campo.COMPENSACION;
import static com.uniride.agent.Aclaraciones.Campo.DESCRIPCION;
import static com.uniride.agent.Aclaraciones.Campo.DESCRIPCION_ACTUAL;
import static com.uniride.agent.Aclaraciones.Campo.ESTADO;
import static com.uniride.agent.Aclaraciones.Campo.NUEVA_DESCRIPCION;
import static com.uniride.agent.Aclaraciones.Campo.NUEVO_TIPO;
import static com.uniride.agent.Aclaraciones.Campo.TIPO_ACTUAL;

import com.uniride.agent.Aclaraciones;
import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetodoCompensacionAgentTools {
    private static final String REGISTRAR = "registrarMetodoCompensacion";
    private static final String ACTUALIZAR = "actualizarMetodoCompensacion";
    private static final String CAMBIAR_ESTADO = "cambiarEstadoMetodoCompensacion";
    private static final String ELIMINAR = "eliminarMetodoCompensacion";

    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;

    @Tool(description = "Consulta métodos propios de compensación de ambos roles. Diez por página desde cero; hayMas indica otra página.")
    public PaginaAgente<MetodoCompensacionResumen> consultarMisMetodosCompensacion(
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        return consultas.metodos(pagina);
    }

    public List<MetodoCompensacionResumen> consultarMisMetodosCompensacion() {
        return consultarMisMetodosCompensacion(null).items();
    }

    @Tool(description = """
            Prepara un método propio de compensación para confirmación. NO registra todavía el método.
            Sirve para ambos roles. La propuesta requiere una decisión explícita del usuario.
            """)
    public OperacionResumen registrarMetodoCompensacion(
            @ToolParam(description = "Tipo DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipo,
            @ToolParam(description = "Descripción del método", required = false) String descripcion) {
        var faltantes = new ArrayList<CampoAclaracion>(2);
        if (tipo == null) faltantes.add(COMPENSACION.obligatorio());
        if (descripcion == null || descripcion.isBlank() || descripcion.length() > 255) {
            faltantes.add(DESCRIPCION.obligatorio());
        }
        if (!faltantes.isEmpty()) {
            String mensaje = faltantes.size() == 2 ? "Indica el tipo de compensación y una descripción."
                    : tipo == null ? "Indica el tipo de compensación." : "Indica una descripción de hasta 255 caracteres.";
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    mensaje).conAclaracion(Aclaraciones.crear(REGISTRAR, faltantes.toArray(CampoAclaracion[]::new)));
        }
        try {
            return confirmaciones.prepararMetodo(new MetodoCompensacionRequest(tipo, descripcion, true));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara editar el tipo o la descripción de un método propio. Identifica el método por tipo o descripción actual.
            Proporciona solo los campos que el usuario pidió cambiar. Conserva el estado activo.
            """)
    public OperacionResumen actualizarMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual,
            @ToolParam(description = "Nuevo tipo DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion nuevoTipo,
            @ToolParam(description = "Nueva descripción", required = false) String nuevaDescripcion) {
        var faltante = validarSelector(ACTUALIZAR, tipoActual, descripcionActual);
        if (faltante != null) return faltante;
        if (nuevaDescripcion == null && nuevoTipo == null) {
            return datosFaltantes("Indica el nuevo tipo o la nueva descripción.",
                    Aclaraciones.alternativa(ACTUALIZAR, NUEVO_TIPO, NUEVA_DESCRIPCION));
        }
        if (nuevaDescripcion != null && (nuevaDescripcion.isBlank() || nuevaDescripcion.length() > 255)) {
            return datosFaltantes("Indica una nueva descripción de hasta 255 caracteres.",
                    Aclaraciones.crear(ACTUALIZAR, NUEVA_DESCRIPCION.obligatorio()));
        }
        return prepararGestion(ACTUALIZAR, tipoActual, descripcionActual,
                () -> confirmaciones.prepararActualizacionMetodo(tipoActual, descripcionActual, nuevoTipo, nuevaDescripcion),
                () -> Aclaraciones.alternativa(ACTUALIZAR, NUEVO_TIPO, NUEVA_DESCRIPCION));
    }

    @Tool(description = """
            Prepara activar o desactivar un método propio por tipo o descripción actual. Requiere un estado deseado explícito.
            Conserva el tipo y la descripción.
            """)
    public OperacionResumen cambiarEstadoMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual,
            @ToolParam(description = "true para activar, false para desactivar", required = false) Boolean activo) {
        var faltante = validarSelector(CAMBIAR_ESTADO, tipoActual, descripcionActual);
        if (faltante != null) return faltante;
        if (activo == null) return datosFaltantes("Indica si quieres activar o desactivar el método.",
                Aclaraciones.crear(CAMBIAR_ESTADO, ESTADO.obligatorio()));
        return prepararGestion(CAMBIAR_ESTADO, tipoActual, descripcionActual,
                () -> confirmaciones.prepararEstadoMetodo(tipoActual, descripcionActual, activo),
                () -> Aclaraciones.crear(CAMBIAR_ESTADO, ESTADO.obligatorio()));
    }

    @Tool(description = """
            Prepara eliminar un método propio por tipo o descripción actual si carece de solicitudes asociadas.
            Úsala solo ante un pedido explícito de eliminación.
            """)
    public OperacionResumen eliminarMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual) {
        var faltante = validarSelector(ELIMINAR, tipoActual, descripcionActual);
        if (faltante != null) return faltante;
        return prepararGestion(ELIMINAR, tipoActual, descripcionActual,
                () -> confirmaciones.prepararEliminacionMetodo(tipoActual, descripcionActual), () -> null);
    }

    private OperacionResumen prepararGestion(String operacion, TipoCompensacion tipoActual, String descripcionActual,
            Supplier<OperacionResumen> preparar, Supplier<AclaracionAgente> otrosCampos) {
        try {
            var resultado = preparar.get();
            if (resultado.estado() == EstadoOperacion.AMBIGUO) {
                var campos = tipoActual == null ? selector(operacion)
                        : Aclaraciones.crear(operacion, DESCRIPCION_ACTUAL.obligatorio()).conRespuestaBreve("valor");
                return resultado.conAclaracion(campos);
            }
            if (resultado.estado() == EstadoOperacion.DATOS_FALTANTES) {
                var campos = tipoActual == null && (descripcionActual == null || descripcionActual.isBlank())
                        ? selector(operacion) : otrosCampos.get();
                return resultado.conAclaracion(campos);
            }
            return resultado;
        } catch (ResourceNotFoundException e) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un método de compensación propio que coincida.");
        } catch (BusinessException | CamposInvalidosException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private static AclaracionAgente selector(String operacion) {
        return Aclaraciones.alternativa(operacion, TIPO_ACTUAL, DESCRIPCION_ACTUAL);
    }

    private static OperacionResumen validarSelector(String operacion, TipoCompensacion tipo, String descripcion) {
        if (tipo == null && (descripcion == null || descripcion.isBlank())) {
            return datosFaltantes("Indica el tipo o la descripción actual del método.", selector(operacion));
        }
        if (descripcion != null && descripcion.length() > 255) {
            return datosFaltantes("Indica una descripción actual de hasta 255 caracteres.",
                    Aclaraciones.crear(operacion, DESCRIPCION_ACTUAL.obligatorio()));
        }
        return null;
    }

    private static OperacionResumen datosFaltantes(String mensaje, AclaracionAgente aclaracion) {
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, mensaje).conAclaracion(aclaracion);
    }
}
