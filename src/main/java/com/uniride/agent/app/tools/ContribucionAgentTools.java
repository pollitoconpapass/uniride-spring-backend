package com.uniride.agent.app.tools;

import static com.uniride.agent.app.clarification.Aclaraciones.Campo.CONDUCTOR;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.DESTINO;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.FECHA;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.HORA;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.ORIGEN;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PASAJEROS;
import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.clarification.Aclaraciones;
import com.uniride.agent.app.dto.AclaracionAgente;
import com.uniride.agent.app.dto.ContribucionFiltros;
import com.uniride.agent.app.dto.ContribucionResultado.Estado;
import com.uniride.agent.app.dto.ContribucionResultado.Opcion;
import com.uniride.agent.app.dto.ContribucionResultado;
import com.uniride.agent.app.dto.OpcionAclaracion;
import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.services.ContribucionService;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ContribucionAgentTools {
    public static final String NOMBRE = "sugerirContribucionViaje";
    public static final String CONTEXTO_FILTROS = "filtrosContribucion";

    private final AgentQueryService consultas;
    private final ContribucionService contribuciones;

    @Tool(name = NOMBRE, description = """
            Consulta la sugerencia referencial de contribución de un viaje para el pasajero.
            Úsala para preguntas de aporte por pasajero, equivalencia en gasolina o un favor alternativo.
            Invócala sin argumentos. Java valida origen, destino, fecha, hora y cantidad a partir del mensaje.
            La consulta solo informa y requiere identificar un viaje único.
            """)
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContribucionResultado sugerirContribucionViaje(ToolContext contexto) {
        if (contexto == null || !(contexto.getContext().get(CONTEXTO_FILTROS) instanceof ContribucionFiltros filtros)) {
            throw new CamposInvalidosException("Indica de qué viaje quieres consultar la contribución.");
        }

        var viajes = consultas.identificarViajes(filtros);
        if (!filtros.tieneReferenciaViaje()) {
            return new ContribucionResultado(Estado.FALTAN_FILTROS,
                    "¿De qué viaje se trata? Indica el origen y destino, el día, la fecha o la hora.",
                    null, List.of(), false, Aclaraciones.alternativa(NOMBRE, ORIGEN, DESTINO, FECHA, HORA));
        }
        if (viajes.isEmpty()) {
            String mensaje = filtros.viajePropio()
                    ? "No encontré próximos viajes programados con un cupo aceptado a tu nombre que coincidan con esos datos."
                    : "No encontré viajes disponibles que coincidan con esos datos. Revisa el origen, destino, fecha u hora.";
            return new ContribucionResultado(Estado.NO_ENCONTRADO, mensaje, null, List.of(), false);
        }
        if (viajes.getTotalElements() > 1) {
            var opciones = viajes.stream().map(AgentQueryService.ViajeContribucion::opcion).toList();
            return ambiguo(opciones, viajes.hasNext());
        }

        var viaje = viajes.getContent().getFirst();
        try {
            var sugerencia = contribuciones.sugerencia(viaje.id(), filtros.pasajeros());
            return new ContribucionResultado(Estado.SUGERIDA, mensaje(sugerencia, filtros.pasajeros()),
                    sugerencia, List.of(), false);
        } catch (CamposInvalidosException datosFaltantes) {
            return new ContribucionResultado(Estado.FALTAN_PASAJEROS, datosFaltantes.getMessage() + ".",
                    null, List.of(viaje.opcion()), false,
                    Aclaraciones.crear(NOMBRE, PASAJEROS.obligatorio()).conRespuestaBreve("valor"));
        }
    }

    private static ContribucionResultado ambiguo(List<Opcion> opciones, boolean hayMas) {
        var primera = opciones.getFirst();
        var segunda = opciones.getLast();
        String descripcionPrimera = describir(primera);
        String descripcionSegunda = describir(segunda);
        AclaracionAgente aclaracion = null;
        String mensaje;
        if (!hayMas && descripcionPrimera.equalsIgnoreCase(descripcionSegunda)) {
            mensaje = "Encontré dos viajes con los mismos datos visibles. Ambos salen de " + descripcionPrimera
                    + ". Indica otra salida que podamos identificar por sus datos.";
        } else {
            String pregunta = "Indica el origen, el destino o el nombre del conductor del viaje que buscas.";
            if (!primera.fecha().equals(segunda.fecha())) {
                pregunta = "¿Qué fecha quieres consultar?";
                aclaracion = selector(FECHA, hayMas, primera.fecha().toString(), segunda.fecha().toString());
            } else if (!primera.hora().equals(segunda.hora())) {
                pregunta = "¿A qué hora sale el viaje que quieres consultar?";
                aclaracion = selector(HORA, hayMas, primera.hora().toString(), segunda.hora().toString());
            } else if (!primera.origen().equalsIgnoreCase(segunda.origen())
                    || !primera.destino().equalsIgnoreCase(segunda.destino())
                    || !Objects.equals(primera.conductor(), segunda.conductor())) {
                aclaracion = Aclaraciones.alternativa(NOMBRE, ORIGEN, DESTINO, CONDUCTOR);
            }
            String inicio = hayMas ? "Encontré varios viajes. Entre ellos están el de " : "Encontré dos viajes. Uno sale de ";
            String separador = hayMas ? " y el de " : " y el otro de ";
            mensaje = inicio + descripcionPrimera + separador + descripcionSegunda + ". " + pregunta;
        }
        return new ContribucionResultado(Estado.AMBIGUA, mensaje, null, opciones, hayMas, aclaracion);
    }

    private static AclaracionAgente selector(Aclaraciones.Campo campo, boolean hayMas, String primero, String segundo) {
        return Aclaraciones.crear(NOMBRE, hayMas ? campo.obligatorio() : campo.conOpciones(List.of(
                new OpcionAclaracion(primero, primero), new OpcionAclaracion(segundo, segundo))));
    }

    private static String describir(Opcion viaje) {
        String conductor = viaje.conductor() == null || viaje.conductor().isBlank() ? "" : " con " + viaje.conductor();
        return "%s a %s el %s a las %s%s"
                .formatted(viaje.origen(), viaje.destino(), viaje.fecha(), viaje.hora(), conductor);
    }

    private static String mensaje(SugerenciaContribucionRespuesta sugerencia, Integer solicitados) {
        String pasajeros = sugerencia.pasajeros() == 1 ? "1 pasajero" : sugerencia.pasajeros() + " pasajeros";
        String texto = String.format(Locale.ROOT,
                "El viaje de %s a %s sale el %s a las %s%s. El costo total referencial es S/ %.2f para %s. "
                        + "El aporte estimado es S/ %.2f por pasajero, equivalente a %.2f litros de gasolina. "
                        + "Como alternativa puedes ofrecer «%s». %s",
                sugerencia.origen(), sugerencia.destino(), sugerencia.fecha(), sugerencia.hora(),
                sugerencia.esHoraPico() ? " (hora pico)" : "", sugerencia.costoTotalEstimado(), pasajeros,
                sugerencia.aporteEstimado(), sugerencia.litrosGasolinaAprox(), sugerencia.sugerenciaFavor(), sugerencia.nota());
        if (solicitados != null && solicitados != sugerencia.pasajeros()) {
            texto += " El cálculo usa la cantidad de pasajeros ya aceptados en el viaje.";
        }
        return texto;
    }
}
