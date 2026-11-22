package com.uniride.agent;

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
        requireRole(Rol.PASAJERO);
        return PaginaAgente.from(buscarDisponibles(origen, destino, dia, fecha, hora,
                pageable(pagina, PAGE_SIZE)).map(this::viajeResumen));
    }

    /** Two rows are sufficient to detect ambiguity across the whole matching set. */
    public Page<RutaRespuesta> identificarRutas(String origen, String destino) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return rutas.consultarRutasAgente(owner.getId(), EstadoRuta.ELIMINADA,
                normalizar(origen), normalizar(destino), PageRequest.of(0, 2)).map(rutaMapper::toRutaRespuesta);
    }

    public Page<BusquedaRespuesta> identificarViajes(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora) {
        requireRole(Rol.PASAJERO);
        return buscarDisponibles(origen, destino, dia, fecha, hora, PageRequest.of(0, 2))
                .map(busquedaMapper::toBusquedaRespuesta);
    }

    /** Conductor-scoped identification for rescheduling; two rows detect ambiguity. */
    public Page<ViajeResumen> identificarViajesConductor(String origen, String destino, String dia,
            LocalDate fecha, LocalTime hora) {
        Usuario owner = requireRole(Rol.CONDUCTOR);
        return viajes.identificarViajesAgente(owner.getId(), normalizar(origen), normalizar(destino),
                normalizarDia(dia), hora, fecha, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA,
                PageRequest.of(0, 2)).map(this::viajeResumen);
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
