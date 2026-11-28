package com.uniride.agent.app.tools;

import static com.uniride.agent.app.intent.CoordinacionConductorIntent.Accion.*;
import static com.uniride.agent.app.tools.CoordinacionAgentTools.*;
import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.agent.app.dto.ViajeResumen;
import com.uniride.agent.app.intent.CoordinacionConductorIntent.Turno;
import com.uniride.dto.responses.AvisoCancelacionRespuesta;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.NotificacionService;
import com.uniride.services.UsuarioService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GestionViajeAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final NotificacionService notificaciones;
    private final UsuarioService usuarios;

    @Tool(description = "Prepara confirmar un viaje del conductor antes del plazo de salida.")
    public OperacionResumen confirmarViaje(ToolContext contexto) {
        return preparar(turno(contexto, CONFIRMAR));
    }

    @Tool(description = "Prepara iniciar un viaje propio confirmado cuando llega la hora de salida.")
    public OperacionResumen iniciarViaje(ToolContext contexto) {
        return preparar(turno(contexto, INICIAR));
    }

    @Tool(description = "Prepara completar un viaje del conductor que está en progreso.")
    public OperacionResumen completarViaje(ToolContext contexto) {
        return preparar(turno(contexto, COMPLETAR));
    }

    @Tool(description = "Prepara cancelar un viaje propio y describe las consecuencias y penalidades.")
    public OperacionResumen cancelarViaje(ToolContext contexto) {
        return preparar(turno(contexto, CANCELAR));
    }

    @Tool(description = "Consulta qué avisos de cancelación se registraron y su estado SMTP.")
    public Object consultarEstadoNotificaciones(ToolContext contexto) {
        Turno turno = turno(contexto, AVISOS);
        return seleccionar(turno, viaje -> {
            if (viaje.estado() != EstadoViaje.CANCELADO) {
                return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "Ese viaje todavía no está cancelado.");
            }
            var avisos = notificaciones.estadoAvisosCancelacion(viaje.viajeId(), usuarios.usuarioActual().getId());
            String mensaje = avisos.isEmpty()
                    ? "El viaje está cancelado. El registro disponible no vincula avisos de cancelación a ese viaje."
                    : "Para el viaje " + describir(viaje) + ". "
                            + avisos.stream().map(this::describirAvisos).collect(Collectors.joining(" "))
                            + " El estado enviado indica que SMTP aceptó el correo.";
            return turno.lectura(Map.of("viajeId", viaje.viajeId(), "avisos", avisos), mensaje, false);
        });
    }

    private OperacionResumen preparar(Turno turno) {
        return conViaje(turno, viaje ->
                confirmaciones.prepararGestionViaje(turno.pedido().accion().tipo(), viaje.viajeId()));
    }

    OperacionResumen conViaje(Turno turno, Function<ViajeResumen, OperacionResumen> ejecutar) {
        return (OperacionResumen) seleccionar(turno, ejecutar);
    }

    private Object seleccionar(Turno turno, Function<ViajeResumen, ?> ejecutar) {
        var pedido = turno.pedido();
        List<EstadoViaje> estados = pedido.filtros().identificaViaje()
                ? List.of(EstadoViaje.values())
                : switch (pedido.accion()) {
                    case AVISOS -> List.of(EstadoViaje.CANCELADO);
                    case COMPLETAR -> List.of(EstadoViaje.EN_PROGRESO);
                    default -> List.of(EstadoViaje.PROGRAMADO);
                };
        var candidatos = consultas.viajesCoordinacion(pedido.filtros(), estados);
        if (candidatos.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un viaje propio con esos datos.");
        }
        if (candidatos.getTotalElements() > 1) {
            return elegir(turno, candidatos, "viaje", "Viaje", ViajeResumen::viajeId, this::describir);
        }
        var viaje = candidatos.getContent().getFirst();
        turno.referir(viaje.viajeId(), null);
        try {
            return ejecutar.apply(viaje);
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException fallo) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, fallo.getMessage());
        }
    }

    private String describir(ViajeResumen viaje) {
        return "#" + viaje.viajeId() + " " + describirSalida(viaje.origen(), viaje.destino(), viaje.fecha(), viaje.hora())
                + " (" + viaje.estado() + ")";
    }

    private String describirAvisos(AvisoCancelacionRespuesta aviso) {
        String asunto = aviso.asunto() == null ? "Aviso " + aviso.tipo() : aviso.asunto();
        return (aviso.paraConductor() ? "Avisos para ti" : "Avisos para pasajeros") + " «"
                + asunto + "» (" + aviso.registrados() + ")"
                + ". Correos enviados " + aviso.enviadosSmtp() + ", en cola " + aviso.enCola()
                + ", en reintento " + aviso.enReintento() + ", descartados " + aviso.descartados()
                + ", sin registro de correo " + aviso.sinRegistroCorreo() + ".";
    }
}
