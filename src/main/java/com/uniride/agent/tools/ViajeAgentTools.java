package com.uniride.agent.tools;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ViajeAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;

    @Tool(description = """
            Busca viajes disponibles para el pasajero por origen, destino, día, fecha y hora.
            Devuelve diez por página; pagina comienza en cero y hayMas permite ofrecer la siguiente.
            Usa únicamente filtros que indicó el usuario.
            """)
    public PaginaAgente<ViajeResumen> buscarViajes(
            @ToolParam(description = "Origen", required = false) String origen,
            @ToolParam(description = "Destino", required = false) String destino,
            @ToolParam(description = "Día de la semana", required = false) String dia,
            @ToolParam(description = "Fecha yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora HH:mm en 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        return consultas.buscar(origen, destino, dia, fecha, hora, pagina);
    }

    public List<ViajeResumen> buscarViajes(String origen, String destino, String dia, LocalTime hora) {
        return buscarViajes(origen, destino, dia, null, hora, null).items();
    }

    @Tool(description = "Consulta viajes propios del conductor, diez por página. pagina inicia en cero; hayMas indica otra página.")
    public PaginaAgente<ViajeResumen> consultarMisViajes(
            @ToolParam(description = "Estado del viaje", required = false) EstadoViaje estado,
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        return consultas.viajes(estado, pagina);
    }

    public List<ViajeResumen> consultarMisViajes(EstadoViaje estado) {
        return consultarMisViajes(estado, null).items();
    }

    @Tool(description = "Consulta solicitudes propias del pasajero, diez por página. pagina inicia en cero; hayMas indica otra página.")
    public PaginaAgente<ViajeResumen> consultarMisSolicitudes(
            @ToolParam(description = "Estado de solicitud", required = false) EstadoSolicitud estado,
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        return consultas.solicitudes(estado, pagina);
    }

    public List<ViajeResumen> consultarMisSolicitudes(EstadoSolicitud estado) {
        return consultarMisSolicitudes(estado, null).items();
    }

    @Tool(description = """
            Prepara un viaje en una ruta propia del conductor. NO crea todavía el viaje.
            Identifica la ruta por origen y destino; si hay varias pregunta cuál desea.
            Devuelve una propuesta que el servidor resuelve ante una decisión explícita del usuario.
            """)
    public OperacionResumen crearViaje(
            @ToolParam(description = "Origen de la ruta propia", required = false) String origenRuta,
            @ToolParam(description = "Destino, si lo indicó el usuario", required = false) String destinoRuta,
            @ToolParam(description = "Fecha yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora HH:mm en 24 horas", required = false) LocalTime hora) {
        if (!rol(Rol.CONDUCTOR))
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede crear viajes.");
        if (blanco(origenRuta))
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica el origen de la ruta propia.");
        if (fecha == null || hora == null)
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica la fecha y la hora del viaje.");
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varias rutas: " + rutas.stream().map(r -> r.origen() + " → " + r.destino())
                                .collect(Collectors.joining("; ")) + ". Indica cuál.");
            return confirmaciones.prepararViaje(rutas.getContent().getFirst().id(), new ViajeRequest(fecha, hora));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara una solicitud de cupo para el pasajero. NO envía todavía la solicitud.
            Identifica el viaje por origen, destino, día, fecha y hora, sin pedir IDs.
            Si hay varias coincidencias pide más datos. Requiere preferencia DINERO, GASOLINA o FAVOR.
            La propuesta requiere una decisión explícita del usuario procesada por el servidor.
            """)
    public OperacionResumen solicitarUnirse(
            @ToolParam(description = "Origen", required = false) String origen,
            @ToolParam(description = "Destino", required = false) String destino,
            @ToolParam(description = "Día", required = false) String dia,
            @ToolParam(description = "Fecha yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora HH:mm en 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "Preferencia DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion preferencia) {
        if (!rol(Rol.PASAJERO))
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un pasajero puede solicitar un cupo.");
        if (preferencia == null)
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica cómo prefieres compensar: DINERO, GASOLINA o FAVOR.");
        try {
            var viajes = consultas.identificarViajes(origen, destino, dia, fecha, hora);
            if (viajes.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un viaje con esos datos.");
            if (viajes.getTotalElements() > 1)
                return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varios viajes: " + viajes.stream()
                                .map(v -> v.fecha() + " " + v.hora() + " " + v.origen() + " → " + v.destino())
                                .collect(Collectors.joining("; ")) + ". Indica la fecha, la hora o el destino exacto.");
            return confirmaciones.prepararSolicitud(viajes.getContent().getFirst().viajeId(),
                    new SolicitudRequest(null, preferencia, null));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private boolean rol(Rol role) { return usuarioService.usuarioActual().getRolPrincipal() == role; }
    private boolean blanco(String value) { return value == null || value.isBlank(); }
}
