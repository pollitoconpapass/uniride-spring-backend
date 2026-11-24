package com.uniride.agent.tools;

import static com.uniride.agent.tools.RutaAgentTools.aclararAmbiguedad;
import static com.uniride.agent.tools.RutaAgentTools.faltantes;
import static com.uniride.agent.tools.RutaAgentTools.filtrosDeRuta;
import static com.uniride.agent.tools.RutaAgentTools.rutaAmbigua;

import com.uniride.agent.Aclaraciones;
import com.uniride.agent.Aclaraciones.Campo;
import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.config.TiempoConfig;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ViajeAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;
    private final Clock clock;

    @Autowired
    public ViajeAgentTools(AgentQueryService consultas, ConfirmationService confirmaciones, UsuarioService usuarioService,
                           Clock clock) {
        this.consultas = consultas;
        this.confirmaciones = confirmaciones;
        this.usuarioService = usuarioService;
        this.clock = clock;
    }

    public ViajeAgentTools(AgentQueryService consultas, ConfirmationService confirmaciones, UsuarioService usuarioService) {
        this(consultas, confirmaciones, usuarioService, Clock.system(TiempoConfig.LIMA));
    }

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
        var campos = filtrosDeRuta(origenRuta, destinoRuta, Campo.ORIGEN, Campo.DESTINO);
        if (blanco(origenRuta)) campos.addFirst(Campo.ORIGEN.obligatorio());
        if (fecha == null) campos.add(Campo.FECHA.obligatorio());
        if (hora == null) campos.add(Campo.HORA.obligatorio());
        if (!campos.isEmpty()) return faltantes("crearViaje", campos);
        try {
            var rutas = consultas.identificarRutas(origenRuta, destinoRuta);
            if (rutas.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré una ruta propia con esos datos.");
            if (rutas.getTotalElements() > 1)
                return rutaAmbigua("crearViaje", rutas, Campo.ORIGEN, Campo.DESTINO);
            return confirmaciones.prepararViaje(rutas.getContent().getFirst().id(), new ViajeRequest(fecha, hora));
        } catch (CamposInvalidosException | SalidaConPocaAnticipacionException e) {
            return corregirHorario("crearViaje", fecha, Campo.FECHA, Campo.HORA, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
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
            Prepara reprogramar la fecha y hora de un viaje propio del conductor. NO lo reprograma todavía.
            Identifica el viaje por origen, destino, día, fecha y hora actuales, sin pedir IDs.
            Si hay varias coincidencias pide más datos. El cambio se aplica al confirmar el resumen.
            """)
    public OperacionResumen reprogramarViaje(
            @ToolParam(description = "Origen de la ruta", required = false) String origen,
            @ToolParam(description = "Destino de la ruta", required = false) String destino,
            @ToolParam(description = "Día actual del viaje", required = false) String dia,
            @ToolParam(description = "Fecha actual yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora actual HH:mm en 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "Nueva fecha yyyy-MM-dd", required = false) LocalDate nuevaFecha,
            @ToolParam(description = "Nueva hora HH:mm en 24 horas", required = false) LocalTime nuevaHora) {
        if (!rol(Rol.CONDUCTOR))
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede reprogramar viajes.");
        var campos = filtrosViaje(origen, destino, dia);
        if (nuevaFecha == null) campos.add(Campo.NUEVA_FECHA.obligatorio());
        if (nuevaHora == null) campos.add(Campo.NUEVA_HORA.obligatorio());
        if (!campos.isEmpty()) return faltantes("reprogramarViaje", campos);
        try {
            var viajes = consultas.identificarViajesConductor(origen, destino, dia, fecha, hora);
            if (viajes.isEmpty())
                return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un viaje propio con esos datos.");
            if (viajes.getTotalElements() > 1)
                return salidasAmbiguas("reprogramarViaje", new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                        "Coinciden varios viajes: " + viajes.stream()
                                .map(v -> v.fecha() + " " + v.hora() + " " + v.origen() + " → " + v.destino())
                                .collect(Collectors.joining("; ")) + ". Indica cuál."),
                        viajes.getTotalElements() == viajes.getNumberOfElements(), viajes.stream()
                                .map(v -> new Salida(v.origen(), v.destino(), v.fecha(), v.hora())).toList());
            if (sinFiltros(origen, destino, dia, fecha, hora)) return identificarSalida("reprogramarViaje");
            return confirmaciones.prepararReprogramacionViaje(viajes.getContent().getFirst().viajeId(),
                    nuevaFecha, nuevaHora);
        } catch (CamposInvalidosException | SalidaConPocaAnticipacionException e) {
            return corregirHorario("reprogramarViaje", nuevaFecha, Campo.NUEVA_FECHA, Campo.NUEVA_HORA, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
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

    private record Salida(String origen, String destino, LocalDate fecha, LocalTime hora) { }

    private static OperacionResumen salidasAmbiguas(String operacion, OperacionResumen resumen, boolean todosVisibles,
                                                     List<Salida> salidas) {
        return aclararAmbiguedad(operacion, resumen, todosVisibles, List.of(
                new RutaAgentTools.Discriminante(Campo.FECHA, salidas.stream().map(v -> Objects.toString(v.fecha(), null)).toList(), false),
                new RutaAgentTools.Discriminante(Campo.HORA, salidas.stream().map(v -> Objects.toString(v.hora(), null)).toList(), false),
                new RutaAgentTools.Discriminante(Campo.ORIGEN, salidas.stream().map(Salida::origen).toList(), true),
                new RutaAgentTools.Discriminante(Campo.DESTINO, salidas.stream().map(Salida::destino).toList(), true)));
    }

    private static List<CampoAclaracion> filtrosViaje(String origen, String destino, String dia) {
        var correcciones = filtrosDeRuta(origen, destino, Campo.ORIGEN, Campo.DESTINO);
        if (dia != null && dia.length() > RutaAgentTools.LONGITUD_FILTRO) correcciones.add(Campo.DIA.obligatorio());
        return correcciones;
    }

    private static OperacionResumen identificarSalida(String operacion) {
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica un dato que identifique el viaje.")
                .conAclaracion(Aclaraciones.alternativa(operacion, Campo.ORIGEN, Campo.DESTINO, Campo.FECHA, Campo.HORA));
    }

    private boolean sinFiltros(String origen, String destino, String dia, LocalDate fecha, LocalTime hora) {
        return blanco(origen) && blanco(destino) && blanco(dia) && fecha == null && hora == null;
    }

    private OperacionResumen corregirHorario(String operacion, LocalDate fecha, Campo campoFecha, Campo campoHora, String mensaje) {
        AclaracionAgente aclaracion = fecha.isBefore(LocalDate.now(clock))
                ? Aclaraciones.crear(operacion, campoFecha.obligatorio())
                : Aclaraciones.alternativa(operacion, campoFecha, campoHora);
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, mensaje).conAclaracion(aclaracion);
    }

    private boolean rol(Rol role) { return usuarioService.usuarioActual().getRolPrincipal() == role; }
    private boolean blanco(String value) { return value == null || value.isBlank(); }
}
