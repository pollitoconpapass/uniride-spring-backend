package com.uniride.agent.support;

import com.uniride.agent.conversation.CoordinacionConductorIntent;
import com.uniride.agent.dto.*;
import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.*;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.mappers.BusquedaMapper;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded queries for tools. No synchronization, locking, notifications or writes. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentQueryService {
    private static final int PAGE_SIZE = 10;
    // shortcut: one proposal covers up to 50 pending requests; raise if bulk cancellation must grow.
    private static final int MAX_CANCELACIONES = 50;
    private final RutaRepository rutas;
    private final ViajeRepository viajes;
    private final MetodoCompensacionRepository metodos;
    private final SolicitudRepository solicitudesRepo;
    private final SolicitudService solicitudes;
    private final UsuarioService usuarios;
    private final RutaMapper rutaMapper;
    private final BusquedaMapper busquedaMapper;
    private final Clock clock;

    public PaginaAgente<RutaResumen> rutas(Integer pagina) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return PaginaAgente.from(rutas.consultarRutasAgente(owner.getId(), EstadoRuta.ELIMINADA,
                null, null, pageable(pagina, PAGE_SIZE)).map(this::rutaResumen));
    }

    public PaginaAgente<ViajeResumen> viajes(EstadoViaje estado, Integer pagina) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return PaginaAgente.from(viajes.consultarViajesAgente(owner.getId(), estado,
                pageable(pagina, PAGE_SIZE)).map(this::viajeResumen));
    }

    public PaginaAgente<ViajeResumen> solicitudes(EstadoSolicitud estado, Integer pagina) {
        requireRole(Rol.PASAJERO);
        PageRequest page = pageable(pagina, PAGE_SIZE);
        return PaginaAgente.from(solicitudes.listarMisSolicitudes(estado, page.getPageNumber(), PAGE_SIZE)
                .map(this::solicitudResumen));
    }

    public PaginaAgente<MetodoCompensacionResumen> metodos(Integer pagina) {
        Usuario owner = usuarios.usuarioActual();
        return PaginaAgente.from(metodos.findByUsuarioIdOrderByIdDesc(owner.getId(), pageable(pagina, PAGE_SIZE))
                .map(m -> new MetodoCompensacionResumen(m.getId(), m.getTipo(), m.getDescripcion(), m.isActivo())));
    }

    public PaginaAgente<ViajeResumen> buscar(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora, Integer pagina) {
        return buscar(origen, destino, dia, fecha, hora, null, null, null, pagina);
    }

    public PaginaAgente<ViajeResumen> buscar(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora, LocalTime horaDesde, LocalTime horaHasta,
            String franja, Integer pagina) {
        requireRole(Rol.PASAJERO);
        LocalTime[] rangoFranja = resolverFranja(franja);
        LocalTime desde = horaDesde != null ? horaDesde : (rangoFranja == null ? null : rangoFranja[0]);
        LocalTime hasta = horaHasta != null ? horaHasta : (rangoFranja == null ? null : rangoFranja[1]);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new CamposInvalidosException("La hora máxima no puede ser anterior a la hora mínima.");
        }
        PageRequest page = pageable(pagina, PAGE_SIZE);
        if (desde == null && hasta == null) {
            return PaginaAgente.from(buscarDisponibles(origen, destino, dia, fecha, hora, page)
                    .map(this::viajeResumen));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return PaginaAgente.from(viajes.buscarViajesRelevantesPorFechaYRango(
                normalizar(origen), normalizar(destino), normalizarDia(dia), hora, desde, hasta,
                null, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA,
                now.toLocalDate(), now.toLocalTime(), fecha, page)
                .map(this::viajeResumen));
    }

    /** Two rows are sufficient to detect ambiguity across the whole matching set. */
    public Page<RutaRespuesta> identificarRutas(String origen, String destino) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return respuestasRutasPropias(owner.getId(), origen, destino, PageRequest.of(0, 2));
    }

    /** ViajeService permite programar sobre cualquier ruta propia que siga sin eliminarse. */
    public Page<RutaRespuesta> rutasParaCrearViaje(String origen, String destino, Integer pagina) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return respuestasRutasPropias(owner.getId(), origen, destino, pageable(pagina, PAGE_SIZE));
    }

    /** Revalida una referencia del servidor sin adquirir el bloqueo de la escritura definitiva. */
    public Optional<RutaRespuesta> rutaParaCrearViaje(Long rutaId) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return rutas.findByIdAndConductorIdAndEstadoNot(rutaId, owner.getId(), EstadoRuta.ELIMINADA)
                .map(rutaMapper::toRutaRespuesta);
    }

    private Page<RutaRespuesta> respuestasRutasPropias(Long conductorId, String origen, String destino, PageRequest pagina) {
        return rutas.consultarRutasAgente(conductorId, EstadoRuta.ELIMINADA,
                normalizar(origen), normalizar(destino), pagina).map(rutaMapper::toRutaRespuesta);
    }

    public Page<BusquedaRespuesta> identificarViajes(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora) {
        requireRole(Rol.PASAJERO);
        return buscarDisponibles(origen, destino, dia, fecha, hora, PageRequest.of(0, 2))
                .map(busquedaMapper::toBusquedaRespuesta);
    }

    public record ViajeContribucion(Long id, ContribucionResultado.Opcion opcion) { }

    /** Identifies candidates without calculating, exposing, or reusing the generic search estimate. */
    public Page<ViajeContribucion> identificarViajes(ContribucionFiltros filtros) {
        Usuario pasajero = requireRole(Rol.PASAJERO);
        if (!filtros.tieneReferenciaViaje()) return Page.empty();

        LocalDateTime ahora = LocalDateTime.now(clock);
        return viajes.identificarParaContribucion(pasajero.getId(), filtros.viajePropio(),
                normalizar(filtros.origen()), normalizar(filtros.destino()), normalizar(filtros.conductor()),
                normalizarDia(filtros.dia()), filtros.hora(), filtros.fecha(), EstadoViaje.PROGRAMADO,
                EstadoRuta.ELIMINADA, ahora.toLocalDate(), ahora.toLocalTime(), PageRequest.of(0, 2))
                .map(viaje -> new ViajeContribucion(viaje.getId(), new ContribucionResultado.Opcion(
                        viaje.getRuta().getOrigen(), viaje.getRuta().getDestino(), viaje.getDia(), viaje.getFecha(),
                        viaje.getHora(), viaje.getRuta().getConductor().getNombre())));
    }

    /** Conductor-scoped identification for rescheduling; two rows detect ambiguity. */
    public Page<ViajeResumen> identificarViajesConductor(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return viajes.identificarViajesAgente(owner.getId(), normalizar(origen), normalizar(destino),
                normalizarDia(dia), hora, fecha, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA,
                PageRequest.of(0, 2)).map(this::viajeResumen);
    }

    public Page<ViajeResumen> viajesCoordinacion(CoordinacionConductorIntent.Filtros filtros, List<EstadoViaje> estados) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return viajes.buscarCoordinacionConductor(owner.getId(), filtros.viajeId(), estados,
                literal(filtros.origen()), literal(filtros.destino()), filtros.fecha(), filtros.hora(),
                pageable(0, PAGE_SIZE)).map(this::viajeResumen);
    }

    public Page<SolicitudConductorResumen> solicitudesRecibidas(CoordinacionConductorIntent.Filtros filtros,
            EstadoSolicitud estado, int pagina) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return solicitudesRepo.buscarRecibidasAgente(owner.getId(), filtros.viajeId(), filtros.solicitudId(), estado,
                literal(filtros.origen()), literal(filtros.destino()), filtros.fecha(), filtros.hora(),
                literal(filtros.pasajero()), pageable(pagina, PAGE_SIZE)).map(s -> {
                    Viaje viaje = s.getViaje();
                    Usuario pasajero = s.getPasajero();
                    return new SolicitudConductorResumen(s.getId(), viaje.getId(),
                            (pasajero.getNombre() + " " + java.util.Objects.toString(pasajero.getApellidos(), "")).strip(),
                            s.getEstado(), s.getPreferenciaCompensacion(), viaje.getRuta().getOrigen(),
                            viaje.getRuta().getDestino(), viaje.getFecha(), viaje.getHora());
                });
    }

    private String literal(String valor) {
        String normalizado = normalizar(valor);
        return normalizado == null ? null : normalizado.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Own pending requests that are still cancellable, oldest trip first to keep lock order stable. */
    public List<SolicitudResumen> candidatasCancelacion(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora) {
        Usuario owner = requireRole(Rol.PASAJERO);
        LocalDateTime now = LocalDateTime.now(clock);
        Page<Solicitud> pendientes = solicitudesRepo.buscarPendientesCancelablesAgente(owner.getId(),
                EstadoSolicitud.PENDIENTE, normalizar(origen), normalizar(destino), normalizarDia(dia), hora, fecha,
                PageRequest.of(0, MAX_CANCELACIONES));
        if (pendientes.hasNext())
            throw new CamposInvalidosException("Tienes más de " + MAX_CANCELACIONES
                    + " solicitudes pendientes; indica un filtro para cancelarlas por partes.");
        return pendientes.stream()
                .filter(s -> LocalDateTime.of(s.getViaje().getFecha(), s.getViaje().getHora()).isAfter(now))
                .map(s -> new SolicitudResumen(s.getId(), s.getViaje().getId(), s.getViaje().getRuta().getOrigen(),
                        s.getViaje().getRuta().getDestino(), s.getViaje().getDia(), s.getViaje().getFecha(),
                        s.getViaje().getHora(), s.getEstado()))
                .toList();
    }

    private Page<Viaje> buscarDisponibles(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora, PageRequest pageable) {
        LocalDateTime now = LocalDateTime.now(clock);
        return viajes.buscarViajesRelevantesPorFecha(normalizar(origen), normalizar(destino), normalizarDia(dia),
                hora, null, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA,
                now.toLocalDate(), now.toLocalTime(), fecha, pageable);
    }

    private Usuario requireRole(Rol role) {
        Usuario user = usuarios.usuarioActual();
        if (user.getRolPrincipal() != role)
            throw new NoPermitidoException("Esta consulta requiere el rol " + role + ".");
        return user;
    }

    private PageRequest pageable(Integer requestedPage, int size) {
        int page = requestedPage == null ? 0 : requestedPage;
        if (page < 0 || page > 10000)
            throw new CamposInvalidosException("La página del agente debe estar entre 0 y 10000.");
        return PageRequest.of(page, size);
    }

    private String normalizar(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 255) throw new CamposInvalidosException("El filtro no puede superar 255 caracteres.");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizarDia(String value) {
        String normalized = normalizar(value);
        return normalized == null ? null : Normalizer.normalize(normalized, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private LocalTime[] resolverFranja(String franja) {
        String normalized = normalizarDia(franja);
        if (normalized == null) return null;
        String limpia = normalized
                .replaceFirst("^(?:por la |en la |de la |al |de |por |en )+", "")
                .trim();
        return switch (limpia) {
            case "madrugada" -> new LocalTime[] { LocalTime.of(0, 0), LocalTime.of(5, 59) };
            case "temprano", "manana temprano" -> new LocalTime[] { LocalTime.of(5, 0), LocalTime.of(9, 0) };
            case "manana" -> new LocalTime[] { LocalTime.of(6, 0), LocalTime.of(12, 0) };
            case "mediodia", "medio dia" -> new LocalTime[] { LocalTime.of(11, 0), LocalTime.of(14, 0) };
            case "tarde" -> new LocalTime[] { LocalTime.of(12, 0), LocalTime.of(18, 0) };
            case "noche" -> new LocalTime[] { LocalTime.of(18, 0), LocalTime.of(23, 59) };
            default -> throw new CamposInvalidosException(
                    "Franja horaria no válida. Usa madrugada, temprano, mañana, mediodía, tarde o noche.");
        };
    }

    private RutaResumen rutaResumen(Ruta r) {
        // Report the persisted state; legacy state recalculation writes and is deliberately not invoked here.
        return new RutaResumen(r.getId(), r.getOrigen(), r.getDestino(), r.getPuntosReferencia(),
                r.getZonasSinParada(), r.getCapacidadMaxima(), r.getEstado());
    }

    private ViajeResumen viajeResumen(Viaje v) {
        Ruta r = v.getRuta();
        return new ViajeResumen(v.getId(), r.getId(), r.getOrigen(), r.getDestino(), v.getDia(),
                v.getFecha(), v.getHora(), r.getPuntosReferencia(), r.getConductor().getNombre(),
                r.getCapacidadMaxima(), v.getEstado(), null);
    }

    private ViajeResumen solicitudResumen(SolicitudRespuesta s) {
        return new ViajeResumen(s.viajeId(), s.rutaId(), s.origen(), s.destino(), s.dia(), s.fecha(),
                s.hora(), null, null, null, null, s.estado());
    }
}
