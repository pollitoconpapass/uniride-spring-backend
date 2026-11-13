package com.uniride.agent.tools;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.RutaService;
import com.uniride.services.UsuarioService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RutaAgentTools {

    private final RutaService rutaService;
    private final UsuarioService usuarioService;

    @Tool(description = """
            Consulta todas las rutas del conductor autenticado: activas, llenas y vencidas, sin las eliminadas.
            Cada ruta incluye su estado; si el usuario pregunta por un estado, filtra el resultado.
            """)
    public List<RutaResumen> consultarMisRutas() {
        return rutaService.listarMisRutas(null, 0, 100).stream()
                .map(this::toResumen)
                .toList();
    }

    @Tool(description = """
            Publica una ruta para el conductor autenticado.
            El destino se toma de la universidad registrada en su perfil.
            """)
    public OperacionResumen crearRuta(
            @ToolParam(description = "Punto de salida de la ruta") String origen,
            @ToolParam(description = "Capacidad máxima de pasajeros") Integer capacidadMaxima,
            @ToolParam(description = "Puntos de referencia del recorrido") String puntosReferencia,
            @ToolParam(description = "Zonas sin parada, solo si el usuario las menciona", required = false) String zonasSinParada,
            @ToolParam(description = "Nota de contribución, solo si el usuario la menciona", required = false) String notaContribucion) {
        if (!esConductor()) {
            return new OperacionResumen(EstadoOperacion.ERROR, null,
                    "Solo un conductor puede publicar rutas. Cambia tu rol a conductor en la aplicación.");
        }
        if (esBlanco(origen) || esBlanco(puntosReferencia)
                || capacidadMaxima == null || capacidadMaxima < 1) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Completa el origen, los puntos de referencia y una capacidad mayor a cero.");
        }

        try {
            RutaRespuesta ruta = rutaService.publicar(new RutaRequest(
                    origen, capacidadMaxima, puntosReferencia, zonasSinParada, notaContribucion));
            return new OperacionResumen(EstadoOperacion.EXITO, ruta.id(),
                    "Ruta " + ruta.origen() + " → " + ruta.destino() + " publicada.");
        } catch (BusinessException | CamposInvalidosException
                | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private boolean esConductor() {
        Usuario usuario = usuarioService.usuarioActual();
        return usuario.getRolPrincipal() == Rol.CONDUCTOR;
    }

    private boolean esBlanco(String valor) {
        return valor == null || valor.isBlank();
    }

    private RutaResumen toResumen(RutaRespuesta ruta) {
        return new RutaResumen(ruta.id(), ruta.origen(), ruta.destino(),
                ruta.puntosReferencia(), ruta.zonasSinParada(),
                ruta.capacidadMaxima(), ruta.estado());
    }
}
