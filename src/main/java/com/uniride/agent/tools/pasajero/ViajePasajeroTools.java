package com.uniride.agent.tools.pasajero;

import static com.uniride.agent.conversation.Aclaraciones.faltantes;
import static com.uniride.agent.conversation.Aclaraciones.filtrosViaje;
import static com.uniride.agent.conversation.Aclaraciones.salidasAmbiguas;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.conversation.Aclaraciones.Salida;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.dto.*;
import com.uniride.agent.support.AgentQueryService;
import com.uniride.dto.requests.SolicitudRequest;
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
public class ViajePasajeroTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;

    @Tool(description = """
            Busca viajes disponibles para el pasajero por origen, destino, día, fecha, hora o franja horaria.
            Permite búsquedas en lenguaje natural resolviendo fechas relativas (hoy, mañana, próximo lunes)
            y franjas (MADRUGADA, TEMPRANO, MANANA, MEDIODIA, TARDE, NOCHE) o rangos (horaDesde, horaHasta).
            Devuelve diez por página; pagina comienza en cero y hayMas permite ofrecer la siguiente.
            Usa únicamente filtros que indicó el usuario.
            """)
    public PaginaAgente<ViajeResumen> buscarViajes(
            @ToolParam(description = "Origen", required = false) String origen,
            @ToolParam(description = "Destino", required = false) String destino,
            @ToolParam(description = "Día de la semana", required = false) String dia,
            @ToolParam(description = "Fecha yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora exacta HH:mm en 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "Hora mínima HH:mm en 24 horas para rango", required = false) LocalTime horaDesde,
            @ToolParam(description = "Hora máxima HH:mm en 24 horas para rango", required = false) LocalTime horaHasta,
            @ToolParam(description = "Franja horaria: MADRUGADA, TEMPRANO, MANANA, MEDIODIA, TARDE o NOCHE", required = false)
            String franja,
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        if (horaDesde == null && horaHasta == null && blanco(franja)) {
            return consultas.buscar(origen, destino, dia, fecha, hora, pagina);
        }
        return consultas.buscar(origen, destino, dia, fecha, hora, horaDesde, horaHasta, franja, pagina);
    }

    public PaginaAgente<ViajeResumen> buscarViajes(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora, Integer pagina) {
        return buscarViajes(origen, destino, dia, fecha, hora, null, null, null, pagina);
    }

    public List<ViajeResumen> buscarViajes(String origen, String destino, String dia, LocalTime hora) {
        return buscarViajes(origen, destino, dia, null, hora, null, null, null, null).items();
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
        var correcciones = filtrosViaje(origen, destino, dia);
        if (preferencia == null && correcciones.isEmpty())
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica cómo prefieres compensar: DINERO, GASOLINA o FAVOR.")
                    .conAclaracion(Aclaraciones.crear("solicitarUnirse", Campo.COMPENSACION.obligatorio()).conOpcionesRapidas());
        if (preferencia == null) correcciones.add(Campo.COMPENSACION.obligatorio());
        if (!correcciones.isEmpty()) return faltantes("solicitarUnirse", correcciones);
        try {
            var viajes = consultas.identificarViajes(origen, destino, dia, fecha, hora);
            if (viajes.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un viaje con esos datos.");
            if (viajes.getTotalElements() > 1)
                return salidasAmbiguas("solicitarUnirse", new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varios viajes: " + viajes.stream()
                                .map(v -> v.fecha() + " " + v.hora() + " " + v.origen() + " → " + v.destino())
                                .collect(Collectors.joining("; ")) + ". Indica un dato que distinga el viaje."),
                        viajes.getTotalElements() == viajes.getNumberOfElements(), viajes.stream()
                                .map(v -> new Salida(v.origen(), v.destino(), v.fecha(), v.hora())).toList());
            if (sinFiltros(origen, destino, dia, fecha, hora)) return identificarSalida("solicitarUnirse");
            return confirmaciones.prepararSolicitud(viajes.getContent().getFirst().viajeId(),
                    new SolicitudRequest(null, preferencia, null));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara cancelar una o varias solicitudes propias pendientes del pasajero. NO las cancela todavía.
            Filtra por origen, destino, día, fecha u hora si el usuario los indicó.
            Si hay varias y el usuario no pidió cancelarlas todas, pregunta cuál.
            La cancelación se aplica al confirmar el resumen.
            """)
    public OperacionResumen cancelarSolicitud(
            @ToolParam(description = "Origen", required = false) String origen,
            @ToolParam(description = "Destino", required = false) String destino,
            @ToolParam(description = "Día de la semana", required = false) String dia,
            @ToolParam(description = "Fecha yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora HH:mm en 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "true solo si pidió cancelar todas las solicitudes pendientes", required = false) Boolean todas) {
        if (!rol(Rol.PASAJERO))
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un pasajero puede cancelar solicitudes propias.");
        var correcciones = filtrosViaje(origen, destino, dia);
        if (!correcciones.isEmpty()) return faltantes("cancelarSolicitud", correcciones);
        try {
            var candidatas = consultas.candidatasCancelacion(origen, destino, dia, fecha, hora);
            if (candidatas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                        "No encontré solicitudes pendientes propias con esos datos.");
            if (candidatas.size() > 1 && !Boolean.TRUE.equals(todas))
                return salidasAmbiguas("cancelarSolicitud", new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Tienes varias solicitudes pendientes: " + candidatas.stream().limit(6)
                                .map(s -> s.origen() + " → " + s.destino() + " (" + s.fecha() + " " + s.hora() + ")")
                                .collect(Collectors.joining("; ")) + ". Indica cuál o pide cancelarlas todas."),
                        true, candidatas.stream().map(s -> new Salida(s.origen(), s.destino(), s.fecha(), s.hora())).toList());
            return confirmaciones.prepararCancelacionSolicitudes(candidatas);
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage())
                    .conAclaracion(Aclaraciones.alternativa("cancelarSolicitud", Campo.ORIGEN, Campo.DESTINO,
                            Campo.FECHA, Campo.HORA));
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private static OperacionResumen identificarSalida(String operacion) {
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica un dato que identifique el viaje.")
                .conAclaracion(Aclaraciones.alternativa(operacion, Campo.ORIGEN, Campo.DESTINO, Campo.FECHA, Campo.HORA));
    }

    private boolean sinFiltros(String origen, String destino, String dia, LocalDate fecha, LocalTime hora) {
        return blanco(origen) && blanco(destino) && blanco(dia) && fecha == null && hora == null;
    }

    private boolean rol(Rol role) { return usuarioService.usuarioActual().getRolPrincipal() == role; }
    private boolean blanco(String value) { return value == null || value.isBlank(); }
}
