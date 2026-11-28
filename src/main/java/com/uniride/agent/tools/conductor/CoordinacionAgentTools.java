package com.uniride.agent.tools.conductor;

import com.uniride.agent.conversation.AgentConversationStore;
import com.uniride.agent.conversation.CoordinacionConductorIntent.Turno;
import com.uniride.agent.conversation.CoordinacionConductorIntent;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.TipoCampoAclaracion;
import com.uniride.exceptions.CamposInvalidosException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.Page;

final class CoordinacionAgentTools {
    private CoordinacionAgentTools() { }

    static Turno turno(ToolContext contexto, CoordinacionConductorIntent.Accion accion) {
        if (contexto == null || !(contexto.getContext().get(CoordinacionConductorIntent.CONTEXTO) instanceof Turno turno)
                || turno.pedido().accion() != accion) {
            throw new CamposInvalidosException("Se requiere una petición explícita del conductor.");
        }
        return turno;
    }

    static <T> OperacionResumen elegir(Turno turno, Page<T> candidatos, String clave,
            String etiqueta, Function<T, Long> id, Function<T, String> descripcion) {
        var opciones = new ArrayList<OpcionAclaracion>();
        for (var elemento : candidatos.getContent()) {
            String label = seguro(descripcion.apply(elemento), 200);
            if (label == null || !AclaracionAgente.textoSeguro(label, 200)) label = etiqueta + " #" + id.apply(elemento);
            opciones.add(new OpcionAclaracion(id.apply(elemento).toString(), (opciones.size() + 1) + ". " + label));
        }
        var campo = new CampoAclaracion(clave, etiqueta, TipoCampoAclaracion.SELECT, true,
                null, opciones, null, null, null, null);
        String operacion = turno.pedido().accion().herramienta();
        String mensaje = "Encontré " + candidatos.getTotalElements() + " coincidencias. Elige una opción."
                + (candidatos.hasNext() ? " Si falta la que buscas, precisa la fecha, la hora o la ruta." : "");
        var aclaracion = new AclaracionAgente(1, null, operacion, "Elegir " + etiqueta.toLowerCase(java.util.Locale.ROOT),
                List.of(campo), null, true, null);
        return new OperacionResumen(EstadoOperacion.AMBIGUO, null, mensaje).conAclaracion(aclaracion);
    }

    static String describirSalida(String origen, String destino, LocalDate fecha, LocalTime hora) {
        return visible(origen) + " → " + visible(destino) + " el " + fecha + " a las " + hora;
    }

    static String visible(String valor) {
        String seguro = seguro(valor, 80);
        return seguro == null ? "Sin descripción" : seguro;
    }

    static String seguro(String valor, int limite) {
        if (valor == null || valor.isBlank() || AgentConversationStore.containsSecrets(valor)) return null;
        String texto = valor.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return texto.length() <= limite ? texto : texto.substring(0, limite - 1) + "…";
    }
}
