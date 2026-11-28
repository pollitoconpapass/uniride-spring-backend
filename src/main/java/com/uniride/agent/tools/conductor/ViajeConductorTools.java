package com.uniride.agent.tools.conductor;

import static com.uniride.agent.conversation.Aclaraciones.faltantes;
import static com.uniride.agent.conversation.Aclaraciones.filtrosDeRuta;
import static com.uniride.agent.conversation.Aclaraciones.filtrosViaje;
import static com.uniride.agent.conversation.Aclaraciones.salidasAmbiguas;
import static com.uniride.agent.conversation.CoordinacionConductorIntent.Accion.*;
import static com.uniride.agent.tools.conductor.CoordinacionAgentTools.*;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.conversation.Aclaraciones.Salida;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.conversation.AgentConversationStore;
import com.uniride.agent.conversation.CoordinacionConductorIntent.Turno;
import com.uniride.agent.conversation.SeleccionRutaViaje;
import com.uniride.agent.dto.*;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.agent.support.AgentQueryService;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.AvisoCancelacionRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.enums.*;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.*;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.NotificacionService;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class ViajeConductorTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;
    private final UsuarioService usuarioService;
    private final Clock clock;
    private final NotificacionService notificaciones;
    private static final String CREAR_VIAJE = "crearViaje";
    private static final String RUTA_CAMBIO = "La ruta elegida cambió o ya no está disponible.";

    @Autowired
    public ViajeConductorTools(AgentQueryService consultas, ConfirmationService confirmaciones, UsuarioService usuarioService,
                           Clock clock, NotificacionService notificaciones) {
        this.consultas = consultas;
        this.confirmaciones = confirmaciones;
        this.usuarioService = usuarioService;
        this.clock = clock;
        this.notificaciones = notificaciones;
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
            var avisos = notificaciones.estadoAvisosCancelacion(viaje.viajeId(), usuarioService.usuarioActual().getId());
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
