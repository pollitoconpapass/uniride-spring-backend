package com.uniride.agent.tools;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.dto.responses.ViajeRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.BusquedaService;
import com.uniride.services.RutaService;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import com.uniride.services.ViajeService;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ViajeAgentTools {

    private final BusquedaService busquedaService;
    private final SolicitudService solicitudService;
    private final RutaService rutaService;
    private final ViajeService viajeService;
    private final UsuarioService usuarioService;

    @Tool(description = """
            Busca viajes disponibles por origen, destino, día de la semana u hora.
            Sin filtros devuelve los próximos viajes con cupo.
            Úsala siempre que el usuario pregunte qué viajes hay.
            """)
    public List<ViajeResumen> buscarViajes(
            @ToolParam(description = "Origen de la ruta, solo si el usuario lo menciona", required = false) String origen,
            @ToolParam(description = "Destino de la ruta, solo si el usuario lo menciona", required = false) String destino,
            @ToolParam(description = "Día de la semana, por ejemplo Lunes, sin tildes", required = false) String dia,
            @ToolParam(description = "Hora exacta de salida en formato HH:mm, 24 horas", required = false) LocalTime hora) {
        return buscar(origen, destino, dia, hora, 5).stream()
                .map(this::toResumen)
                .toList();
    }

    @Tool(description = """
            Consulta los viajes del conductor autenticado.
            Sin estado devuelve los más recientes, hasta 20.
            """)
    public List<ViajeResumen> consultarMisViajes(
            @ToolParam(description = "Estado: PROGRAMADO, EN_PROGRESO, COMPLETADO, CANCELADO o VENCIDO", required = false) EstadoViaje estado) {
        List<ViajeResumen> viajes = new ArrayList<>();
        for (RutaRespuesta ruta : rutaService.listarMisRutas(null, 0, 100).getContent()) {
            for (ViajeRespuesta viaje : viajeService.listarPorRuta(ruta.id(), 0, 100).getContent()) {
                if (estado != null && viaje.estado() != estado) {
                    continue;
                }
                viajes.add(new ViajeResumen(viaje.id(), ruta.id(), ruta.origen(), ruta.destino(),
                        viaje.dia(), viaje.fecha(), viaje.hora(), ruta.puntosReferencia(),
                        ruta.conductorNombre(), ruta.capacidadMaxima(), viaje.estado(), null));
            }
        }
        return viajes.stream()
                .sorted(Comparator.comparing(ViajeResumen::fecha)
                        .thenComparing(ViajeResumen::hora).reversed())
                .limit(20)
                .toList();
    }

    @Tool(description = """
            Consulta las solicitudes de viaje del pasajero autenticado.
            Úsala cuando un pasajero pregunte por sus viajes o solicitudes.
            """)
    public List<ViajeResumen> consultarMisSolicitudes(
            @ToolParam(description = "Estado: PENDIENTE, ACEPTADA, RECHAZADA o CANCELADA", required = false) EstadoSolicitud estado) {
        return solicitudService.listarMisSolicitudes(estado, 0, 10).stream()
                .map(this::toResumen)
                .toList();
    }

    @Tool(description = """
            Programa un viaje en una ruta propia del conductor.
            Identifica la ruta por su origen y, si hace falta, su destino.
            La fecha y hora deben ser futuras con al menos 12 horas de anticipación.
            """)
    public OperacionResumen crearViaje(
            @ToolParam(description = "Origen de la ruta del conductor") String origenRuta,
            @ToolParam(description = "Destino de la ruta, solo si el usuario lo menciona", required = false) String destinoRuta,
            @ToolParam(description = "Fecha del viaje en formato yyyy-MM-dd") LocalDate fecha,
            @ToolParam(description = "Hora de salida en formato HH:mm, 24 horas") LocalTime hora) {
        if (!esConductor()) {
            return new OperacionResumen(EstadoOperacion.ERROR, null,
                    "Solo un conductor puede crear viajes. Cambia tu rol a conductor en la aplicación.");
        }
        if (esBlanco(origenRuta)) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el origen de la ruta en la que quieres crear el viaje.");
        }
        if (fecha == null || hora == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica la fecha y la hora del viaje.");
        }

        List<RutaRespuesta> rutas = rutaService.listarMisRutas(null, 0, 100).getContent().stream()
                .filter(ruta -> contiene(ruta.origen(), origenRuta)
                        && (esBlanco(destinoRuta) || contiene(ruta.destino(), destinoRuta)))
                .toList();
        if (rutas.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré una ruta propia con ese origen y destino.");
        }
        if (rutas.size() > 1) {
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Coinciden varias rutas: " + detalleRutas(rutas) + ". Indica cuál.");
        }

        try {
            ViajeRespuesta viaje = viajeService.crear(rutas.get(0).id(), new ViajeRequest(fecha, hora));
            return new OperacionResumen(EstadoOperacion.EXITO, viaje.id(),
                    "Viaje creado para el " + viaje.dia() + " " + viaje.fecha()
                            + " a las " + viaje.hora() + ".");
        } catch (BusinessException | CamposInvalidosException
                | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Envía una solicitud para unirse a un viaje.
            La solicitud queda PENDIENTE y el conductor decide si la acepta o rechaza.
            No pidas ids: identifica el viaje por origen, destino, día, fecha u hora.
            """)
    public OperacionResumen solicitarUnirse(
            @ToolParam(description = "Origen de la ruta, solo si el usuario lo menciona", required = false) String origen,
            @ToolParam(description = "Destino de la ruta, solo si el usuario lo menciona", required = false) String destino,
            @ToolParam(description = "Día de la semana, por ejemplo Lunes, sin tildes", required = false) String dia,
            @ToolParam(description = "Fecha exacta del viaje en formato yyyy-MM-dd", required = false) LocalDate fecha,
            @ToolParam(description = "Hora exacta de salida en formato HH:mm, 24 horas", required = false) LocalTime hora,
            @ToolParam(description = "Preferencia de compensación: DINERO, GASOLINA o FAVOR") TipoCompensacion preferencia) {
        if (!esPasajero()) {
            return new OperacionResumen(EstadoOperacion.ERROR, null,
                    "Solo un pasajero puede solicitar unirse a un viaje. Cambia tu rol a pasajero en la aplicación.");
        }
        if (preferencia == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica cómo prefieres compensar: DINERO, GASOLINA o FAVOR.");
        }

        List<BusquedaRespuesta> coincidencias = buscar(origen, destino, dia, hora, 20).stream()
                .filter(viaje -> fecha == null || fecha.equals(viaje.fecha()))
                .toList();
        if (coincidencias.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré un viaje con esos datos.");
        }
        if (coincidencias.size() > 1) {
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Coinciden varios viajes: " + detalleViajes(coincidencias)
                            + ". Indica la fecha, la hora o el destino exacto.");
        }

        BusquedaRespuesta viaje = coincidencias.get(0);
        try {
            SolicitudRespuesta solicitud = solicitudService.crear(viaje.viajeId(),
                    new SolicitudRequest(null, preferencia, null));
            return new OperacionResumen(EstadoOperacion.EXITO, solicitud.id(),
                    "Solicitud enviada al conductor para el viaje del " + viaje.dia()
                            + " " + viaje.fecha() + " a las " + viaje.hora()
                            + " (" + viaje.origen() + " → " + viaje.destino()
                            + "). Queda PENDIENTE hasta que el conductor la acepte.");
        } catch (BusinessException | CamposInvalidosException
                | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private List<BusquedaRespuesta> buscar(String origen, String destino, String dia,
            LocalTime hora, int size) {
        return busquedaService.buscar(origen, destino, normalizarDia(dia), hora, false, 0, size)
                .getContent();
    }

    private boolean esConductor() {
        Usuario usuario = usuarioService.usuarioActual();
        return usuario.getRolPrincipal() == Rol.CONDUCTOR;
    }

    private boolean esPasajero() {
        Usuario usuario = usuarioService.usuarioActual();
        return usuario.getRolPrincipal() == Rol.PASAJERO;
    }

    private boolean contiene(String valor, String texto) {
        return valor != null && texto != null
                && valor.toLowerCase().contains(texto.trim().toLowerCase());
    }

    private boolean esBlanco(String valor) {
        return valor == null || valor.isBlank();
    }

    private String normalizarDia(String dia) {
        if (esBlanco(dia)) {
            return null;
        }
        String sinTildes = Normalizer.normalize(dia.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return switch (sinTildes.toLowerCase()) {
            case "lunes" -> "Lunes";
            case "martes" -> "Martes";
            case "miercoles" -> "Miercoles";
            case "jueves" -> "Jueves";
            case "viernes" -> "Viernes";
            case "sabado" -> "Sabado";
            case "domingo" -> "Domingo";
            default -> sinTildes;
        };
    }

    private String detalleViajes(List<BusquedaRespuesta> viajes) {
        return viajes.stream().limit(3)
                .map(viaje -> viaje.dia() + " " + viaje.fecha() + " " + viaje.hora()
                        + " " + viaje.origen() + " → " + viaje.destino())
                .collect(Collectors.joining("; "));
    }

    private String detalleRutas(List<RutaRespuesta> rutas) {
        return rutas.stream().limit(3)
                .map(ruta -> ruta.origen() + " → " + ruta.destino())
                .collect(Collectors.joining("; "));
    }

    private ViajeResumen toResumen(BusquedaRespuesta viaje) {
        return new ViajeResumen(viaje.viajeId(), viaje.rutaId(), viaje.origen(), viaje.destino(),
                viaje.dia(), viaje.fecha(), viaje.hora(), viaje.puntosReferencia(),
                viaje.conductorNombre(), viaje.capacidadMaxima(), null, null);
    }

    private ViajeResumen toResumen(SolicitudRespuesta solicitud) {
        return new ViajeResumen(solicitud.viajeId(), solicitud.rutaId(), solicitud.origen(),
                solicitud.destino(), solicitud.dia(), solicitud.fecha(), solicitud.hora(),
                null, null, null, null, solicitud.estado());
    }
}
