package com.uniride.agent.conversation;

import com.uniride.dto.responses.RutaRespuesta;
import java.util.Map;

/** Server-side selection state passed through ToolContext, outside the model argument schema. */
public final class SeleccionRutaViaje {
    public static final String CONTEXTO = "seleccionRutaViaje";
    public static final Estado INICIAL = new Estado(null, Map.of(), 0, false, false);

    public record Estado(RutaRespuesta elegida, Map<String, RutaRespuesta> opciones,
                         int pagina, boolean hayMas, boolean limpiarFiltros) {
        public Estado { opciones = Map.copyOf(opciones); }

        Estado elegir(String referencia) {
            var ruta = opciones.get(referencia);
            if (ruta == null) throw new com.uniride.exceptions.CamposInvalidosException("Selecciona una de las rutas ofrecidas.");
            return new Estado(ruta, Map.of(), 0, false, false);
        }

        Estado pagina(int siguiente) {
            return new Estado(null, opciones, siguiente, hayMas, limpiarFiltros);
        }
    }

    private Estado estado;

    public SeleccionRutaViaje(Estado estado) { this.estado = estado; }

    public Estado estado() { return estado; }

    public void actualizar(Estado nuevo) { estado = nuevo; }
}
