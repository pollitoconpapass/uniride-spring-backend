package com.uniride.agent.app.tools;

import static com.uniride.agent.app.intent.CoordinacionConductorIntent.Accion.*;
import static com.uniride.agent.app.tools.CoordinacionAgentTools.*;
import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.agent.app.dto.PaginaAgente;
import com.uniride.agent.app.dto.SolicitudConductorResumen;
import com.uniride.agent.app.intent.CoordinacionConductorIntent.Turno;
import com.uniride.dto.responses.DetalleSolicitudRecibidaUs14Respuesta;
import com.uniride.dto.responses.PerfilPasajeroUs14Respuesta;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.SolicitudesRecibidasUs14Service;
import java.util.ArrayList;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SolicitudesConductorAgentTools {
    private final AgentQueryService consultas;
    private final SolicitudesRecibidasUs14Service detalles;
    private final ConfirmationService confirmaciones;
    private final GestionViajeAgentTools viajes;

    @Tool(description = "Consulta solicitudes recibidas en los viajes propios del conductor.")
    public PaginaAgente<SolicitudConductorResumen> consultarSolicitudesRecibidas(ToolContext contexto) {
        Turno turno = turno(contexto, LISTAR);
        var pedido = turno.pedido();
        var pagina = consultas.solicitudesRecibidas(pedido.filtros(), pedido.estado(), pedido.pagina())
                .map(this::limpiarResumen);
        if (pagina.getTotalElements() == 1 && !pagina.isEmpty()) {
            var solicitud = pagina.getContent().getFirst();
            turno.referir(solicitud.viajeId(), solicitud.solicitudId());
        } else if (!pagina.isEmpty() && pagina.getTotalElements() == pagina.getNumberOfElements()
                && pagina.stream().map(SolicitudConductorResumen::viajeId).distinct().count() == 1) {
            turno.referir(pagina.getContent().getFirst().viajeId(), null);
        } else {
            turno.referir(pedido.filtros().viajeId(), null);
        }
        String mensaje = pagina.isEmpty()
                ? "No encontré solicitudes recibidas con esos datos."
                : "Encontré " + pagina.getTotalElements() + " solicitudes. "
                        + pagina.stream().map(s -> describirSolicitud(s) + ".")
                                .collect(Collectors.joining(" "))
                        + (pagina.hasNext() ? " Puedes pedir la página siguiente." : "");
        return turno.lectura(PaginaAgente.from(pagina), mensaje, pagina.hasNext());
    }

    @Tool(description = "Consulta el perfil compartido y el mensaje de una solicitud recibida.")
    public Object consultarDetalleSolicitudRecibida(ToolContext contexto) {
        return solicitud(turno(contexto, DETALLE));
    }

    @Tool(description = "Prepara aceptar una solicitud recibida. Requiere confirmación del conductor.")
    public Object aceptarSolicitud(ToolContext contexto) {
        return solicitud(turno(contexto, ACEPTAR));
    }

    @Tool(description = "Prepara rechazar una solicitud recibida. Requiere confirmación del conductor.")
    public Object rechazarSolicitud(ToolContext contexto) {
        return solicitud(turno(contexto, RECHAZAR));
    }

    @Tool(description = "Prepara rechazar las solicitudes pendientes del viaje propio elegido.")
    public OperacionResumen rechazarSolicitudesPendientes(ToolContext contexto) {
        Turno turno = turno(contexto, RECHAZAR_VARIAS);
        var pedido = turno.pedido();
        var elegidas = pedido.solicitudesElegidas();
        if (!elegidas.isEmpty()) {
            var primera = consultas.solicitudesRecibidas(pedido.filtros().elegirSolicitud(elegidas.getFirst()), null, 0);
            if (primera.isEmpty()) return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré las solicitudes elegidas en tus viajes.");
            Long viajeId = primera.getContent().getFirst().viajeId();
            turno.referir(viajeId, null);
            try {
                return confirmaciones.prepararRechazoPendientes(viajeId, pedido.motivo(), elegidas);
            } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException fallo) {
                return new OperacionResumen(EstadoOperacion.ERROR, null, fallo.getMessage());
            }
        }
        return viajes.conViaje(turno,
                viaje -> confirmaciones.prepararRechazoPendientes(viaje.viajeId(), pedido.motivo(), elegidas));
    }

    private Object solicitud(Turno turno) {
        var pedido = turno.pedido();
        var candidatas = consultas.solicitudesRecibidas(pedido.filtros(), pedido.estado(), 0);
        if (candidatas.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré una solicitud recibida con esos datos.");
        }
        if (candidatas.getTotalElements() > 1) {
            return elegir(turno, candidatas, "solicitud", "Solicitud", SolicitudConductorResumen::solicitudId,
                    this::describirSolicitud);
        }
        var solicitud = candidatas.getContent().getFirst();
        turno.referir(solicitud.viajeId(), solicitud.solicitudId());
        try {
            return pedido.accion() == DETALLE ? consultarDetalle(turno, solicitud)
                    : confirmaciones.prepararGestionSolicitud(pedido.accion().tipo(), solicitud.solicitudId(), pedido.motivo());
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException fallo) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, fallo.getMessage());
        }
    }

    private DetalleSolicitudRecibidaUs14Respuesta consultarDetalle(Turno turno, SolicitudConductorResumen solicitud) {
        var detalle = detalles.detalle(solicitud.solicitudId());
        var perfil = detalle.pasajero();
        var visible = new PerfilPasajeroUs14Respuesta(perfil.usuarioId(), seguro(perfil.nombre(), 80),
                seguro(perfil.apellidos(), 100), seguro(perfil.carrera(), 120), seguro(perfil.distrito(), 80),
                seguro(perfil.universidad(), 120), seguro(perfil.gustos(), 200), seguro(perfil.hobbies(), 200));
        var limpio = new DetalleSolicitudRecibidaUs14Respuesta(detalle.solicitudId(), detalle.rutaId(), detalle.viajeId(),
                detalle.estado(), seguro(detalle.mensajeSolicitud(), 400), visible, detalle.mensaje());
        var mensaje = new ArrayList<String>();
        mensaje.add(describirSolicitud(solicitud) + ".");
        agregar(mensaje, "Carrera", visible.carrera());
        agregar(mensaje, "Distrito", visible.distrito());
        agregar(mensaje, "Universidad", visible.universidad());
        agregar(mensaje, "Gustos", visible.gustos());
        agregar(mensaje, "Pasatiempos", visible.hobbies());
        if (limpio.mensajeSolicitud() != null) mensaje.add("Mensaje del pasajero «" + limpio.mensajeSolicitud() + "».");
        if (limpio.mensaje() != null) mensaje.add(limpio.mensaje());
        return turno.lectura(limpio, String.join(" ", mensaje), false);
    }

    private void agregar(java.util.List<String> mensaje, String campo, String valor) {
        if (valor != null) mensaje.add(campo + ": " + valor + ".");
    }

    private String describirSolicitud(SolicitudConductorResumen s) {
        return "Solicitud #" + s.solicitudId() + " de " + visible(s.pasajero()) + " (" + s.estado() + ") para "
                + describirSalida(s.origen(), s.destino(), s.fecha(), s.hora()) + ". Compensación " + s.preferencia();
    }

    private SolicitudConductorResumen limpiarResumen(SolicitudConductorResumen s) {
        return new SolicitudConductorResumen(s.solicitudId(), s.viajeId(), visible(s.pasajero()),
                s.estado(), s.preferencia(), visible(s.origen()), visible(s.destino()), s.fecha(), s.hora());
    }
}
