package com.uniride.agent.app.tools;

import static com.uniride.agent.app.tools.RutaAgentTools.aclararAmbiguedad;
import static com.uniride.agent.app.tools.RutaAgentTools.faltantes;
import static com.uniride.agent.app.tools.RutaAgentTools.filtrosDeRuta;
import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.clarification.Aclaraciones.Campo;
import com.uniride.agent.app.clarification.Aclaraciones;
import com.uniride.agent.app.clarification.SeleccionRutaViaje;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.AclaracionAgente;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OpcionAclaracion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.agent.app.dto.PaginaAgente;
import com.uniride.agent.app.dto.ViajeResumen;
import com.uniride.agent.core.AgentConversationStore;
import com.uniride.config.TiempoConfig;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class ViajeAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;
    private final Clock clock;
    private static final String CREAR_VIAJE = "crearViaje";
    private static final String RUTA_CAMBIO = "La ruta elegida cambió o ya no está disponible.";

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
            @ToolParam(description = "Hora HH:mm en 24 horas", required = false) LocalTime hora,
            ToolContext contexto) {
        if (!rol(Rol.CONDUCTOR))
            return new OperacionResumen(EstadoOperacion.ERROR, null, "Solo un conductor puede crear viajes.");
        var turno = contexto != null && contexto.getContext().get(SeleccionRutaViaje.CONTEXTO) instanceof SeleccionRutaViaje s
                ? s : null;
        var seleccion = turno == null ? SeleccionRutaViaje.INICIAL : turno.estado();
        var ruta = seleccion.elegida();
        if (ruta != null && consultas.rutaParaCrearViaje(ruta.id()).filter(ruta::equals).isEmpty())
            return ofrecerAlternativas(turno, fecha, hora, RUTA_CAMBIO);
        if (ruta == null) {
            boolean filtrado = !blanco(origenRuta) || !blanco(destinoRuta);
            if (!filtrosDeRuta(origenRuta, destinoRuta, Campo.ORIGEN, Campo.DESTINO).isEmpty()) {
                return ofrecerAlternativas(turno, fecha, hora, "El origen o destino indicado es demasiado largo.");
            }
            var rutas = consultas.rutasParaCrearViaje(origenRuta, destinoRuta, seleccion.pagina());
            if (rutas.isEmpty() && seleccion.pagina() > 0)
                rutas = consultas.rutasParaCrearViaje(origenRuta, destinoRuta, 0);
            if (filtrado && rutas.isEmpty())
                return ofrecerAlternativas(turno, fecha, hora, "No encontré una ruta propia con esos datos.");
            if (!filtrado || rutas.getTotalElements() != 1 || !seleccion.opciones().isEmpty()) {
                String detalle = filtrado && rutas.getTotalElements() > 1 ? "Coinciden varias de tus rutas." : null;
                return opcionesRuta(turno, rutas, fecha, hora, detalle, false);
            }
            ruta = rutas.getContent().getFirst();
        }
        if (turno != null) turno.actualizar(new SeleccionRutaViaje.Estado(ruta, Map.of(), 0, false, false));
        var horario = camposHorario(fecha, hora);
        if (!horario.isEmpty()) return faltantes(CREAR_VIAJE, horario);
        try {
            return confirmaciones.prepararViaje(ruta, new ViajeRequest(fecha, hora));
        } catch (CamposInvalidosException | SalidaConPocaAnticipacionException e) {
            return corregirHorario(CREAR_VIAJE, fecha, Campo.FECHA, Campo.HORA, e.getMessage());
        } catch (ResourceNotFoundException e) {
            return ofrecerAlternativas(turno, fecha, hora, RUTA_CAMBIO);
        } catch (BusinessException e) {
            if (consultas.rutaParaCrearViaje(ruta.id()).isEmpty())
                return ofrecerAlternativas(turno, fecha, hora, RUTA_CAMBIO);
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        } catch (NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    public OperacionResumen crearViaje(String origenRuta, String destinoRuta, LocalDate fecha, LocalTime hora) {
        return crearViaje(origenRuta, destinoRuta, fecha, hora, null);
    }

    private OperacionResumen ofrecerAlternativas(SeleccionRutaViaje turno, LocalDate fecha, LocalTime hora, String detalle) {
        return opcionesRuta(turno, consultas.rutasParaCrearViaje(null, null, 0), fecha, hora, detalle, true);
    }

    private OperacionResumen opcionesRuta(SeleccionRutaViaje turno, Page<RutaRespuesta> rutas,
                                         LocalDate fecha, LocalTime hora, String detalle, boolean limpiarFiltros) {
        if (rutas.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    (detalle == null ? "" : detalle + " ")
                            + "Registra una ruta antes de programar un viaje. No tienes rutas disponibles.");
        }
        var opciones = new ArrayList<OpcionAclaracion>();
        var referencias = new LinkedHashMap<String, RutaRespuesta>();
        int ordinal = rutas.getNumber() * 10;
        for (var ruta : rutas.getContent()) {
            String referencia = UUID.randomUUID().toString();
            referencias.put(referencia, ruta);
            opciones.add(new OpcionAclaracion(referencia, etiquetaRuta(++ordinal, ruta)));
        }
        if (turno != null) {
            turno.actualizar(new SeleccionRutaViaje.Estado(null, referencias, rutas.getNumber(),
                    rutas.hasNext(), limpiarFiltros));
        }
        var campos = camposHorario(fecha, hora);
        campos.addFirst(Campo.RUTA.conOpciones(opciones));
        var aclaracion = Aclaraciones.crear(CREAR_VIAJE, campos.toArray(CampoAclaracion[]::new));
        if (campos.size() == 1) aclaracion = aclaracion.conOpcionesRapidas().conRespuestaBreve("valor");
        String mensaje = (detalle == null ? "" : detalle + " ") + "Selecciona una de tus rutas registradas."
                + (fecha == null ? " Indica la fecha de salida." : "") + (hora == null ? " Indica la hora de salida." : "");
        if (rutas.hasNext() || rutas.hasPrevious()) {
            mensaje += " Página " + (rutas.getNumber() + 1) + " de " + rutas.getTotalPages() + "."
                    + (rutas.hasNext() ? " Escribe «siguiente página» para ver más rutas." : "")
                    + (rutas.hasPrevious() ? " Escribe «página anterior» para volver." : "")
                    + " También puedes indicar un origen.";
        }
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, mensaje).conAclaracion(aclaracion);
    }

    private List<CampoAclaracion> camposHorario(LocalDate fecha, LocalTime hora) {
        var campos = new ArrayList<CampoAclaracion>();
        if (fecha == null) campos.add(Campo.FECHA.obligatorio());
        if (hora == null) campos.add(Campo.HORA.obligatorio());
        return campos;
    }

    private String etiquetaRuta(int ordinal, RutaRespuesta ruta) {
        String origen = fragmento(ruta.origen(), 60, "Origen sin descripción disponible");
        String destino = fragmento(ruta.destino(), 60, "Destino sin descripción disponible");
        String referencias = fragmento(ruta.puntosReferencia(), 80, "");
        return ordinal + ". " + origen + " → " + destino + " · Capacidad " + ruta.capacidadMaxima()
                + (referencias.isBlank() ? "" : " · " + referencias);
    }

    private String fragmento(String texto, int maximo, String alternativa) {
        if (texto == null) return alternativa;
        String limpio = texto.replaceAll("\\s+", " ").strip();
        if (!AclaracionAgente.textoSeguro(limpio, limpio.length()) || AgentConversationStore.containsSecrets(limpio))
            return alternativa;
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo - 1) + "…";
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
