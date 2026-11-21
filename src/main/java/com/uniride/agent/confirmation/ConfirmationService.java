package com.uniride.agent.confirmation;

import com.uniride.agent.AgentConversationStore;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.entities.*;
import com.uniride.enums.Rol;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import com.uniride.services.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The model can prepare operations. Only the authenticated HTTP confirmation executes them. */
@Service
@RequiredArgsConstructor
public class ConfirmationService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final ConfirmationRepository confirmations;
    private final UsuarioService usuarios;
    private final RutaService rutas;
    private final ViajeService viajes;
    private final SolicitudService solicitudes;
    private final MetodoCompensacionService metodos;
    private final RutaRepository rutaRepository;
    private final ViajeRepository viajeRepository;
    private final PerfilRepository perfiles;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;

    @Transactional(timeout = 10)
    public OperacionResumen prepararRuta(RutaRequest request) {
        Usuario usuario = autorizado(TipoOperacion.CREAR_RUTA);
        validar(request);
        String destino = rutas.validarPublicacion(request);
        return guardar(usuario, TipoOperacion.CREAR_RUTA, null, request, mapper.valueToTree(destino),
                "Publicar ruta desde " + request.origen() + " hacia " + destino
                        + ", capacidad " + request.capacidadMaxima() + ". Referencias: " + request.puntosReferencia()
                        + opcional(". Zonas sin parada: ", request.zonasSinParada())
                        + opcional(". Contribución: ", request.notaContribucion()) + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararViaje(Long rutaId, ViajeRequest request) {
        Usuario usuario = autorizado(TipoOperacion.CREAR_VIAJE);
        validar(request);
        Ruta ruta = viajes.validarCreacion(rutaId, request);
        return guardar(usuario, TipoOperacion.CREAR_VIAJE, rutaId, request, snapshot(ruta),
                "Programar un viaje desde " + ruta.getOrigen() + " hacia " + ruta.getDestino()
                        + " el " + request.fecha() + " a las " + request.hora()
                        + ", capacidad " + ruta.getCapacidadMaxima() + ". Referencias: " + ruta.getPuntosReferencia() + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararSolicitud(Long viajeId, SolicitudRequest request) {
        Usuario usuario = autorizado(TipoOperacion.SOLICITAR_UNIRSE);
        validar(request);
        Viaje viaje = solicitudes.validarCreacion(viajeId, request);
        return guardar(usuario, TipoOperacion.SOLICITAR_UNIRSE, viajeId, request, snapshot(viaje),
                "Solicitar un cupo desde " + viaje.getRuta().getOrigen() + " hacia " + viaje.getRuta().getDestino()
                        + " el " + viaje.getFecha() + " a las " + viaje.getHora()
                        + ", compensación " + request.preferenciaCompensacion()
                        + opcional(". Mensaje: ", request.mensaje())
                        + ". La solicitud quedará PENDIENTE de aceptación por el conductor.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararMetodo(MetodoCompensacionRequest request) {
        Usuario usuario = autorizado(TipoOperacion.REGISTRAR_METODO);
        validar(request);
        metodos.validarRegistro(request);
        return guardar(usuario, TipoOperacion.REGISTRAR_METODO, null, request, mapper.createObjectNode(),
                "Registrar tu método " + request.tipo() + ": " + request.descripcion().trim() + ".");
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen obtener(UUID id) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen cancelar(UUID id) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA)
            throw new BusinessException("La operación ya fue ejecutada; no se puede cancelar la confirmación.");
        if (pending.getEstado() == EstadoConfirmacion.PENDIENTE) pending.setEstado(EstadoConfirmacion.CANCELADA);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen confirmar(UUID id) {
        PendingConfirmation pending = propia(id);
        // A replay returns the committed receipt, without invoking any business service.
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA) return resumen(pending);
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EXPIRADA) return resumen(pending);
        if (pending.getEstado() == EstadoConfirmacion.CANCELADA)
            throw new BusinessException("La confirmación fue cancelada. Prepara una nueva operación.");

        Usuario usuario = usuarios.usuarioActual();
        // FOR SHARE keeps the current role stable while remaining compatible with notification FKs.
        // Method registration needs an exclusive user lock: its existing duplicate check has no
        // unique DB constraint. Choose it before acquiring a shared lock to avoid lock upgrades.
        entityManager.refresh(usuario, pending.getTipo() == TipoOperacion.REGISTRAR_METODO
                ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
        exigirRol(usuario, pending.getTipo());
        OperacionResumen result = ejecutar(pending, usuario);
        pending.setResultado(mapper.writeValueAsString(result));
        pending.setEstado(EstadoConfirmacion.EJECUTADA);
        pending.setEjecutada(clock.instant());
        confirmations.flush();
        return resumen(pending);
    }

    private OperacionResumen ejecutar(PendingConfirmation pending, Usuario usuario) {
        switch (pending.getTipo()) {
            case CREAR_RUTA -> {
                RutaRequest request = mapper.readValue(pending.getParametros(), RutaRequest.class);
                validar(request);
                Perfil perfil = perfiles.findByUsuarioId(usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
                entityManager.refresh(perfil, LockModeType.PESSIMISTIC_READ);
                comprobarSnapshot(pending, mapper.valueToTree(rutas.validarPublicacion(request)));
                var created = rutas.publicar(request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Ruta " + created.origen() + " → " + created.destino() + " publicada.");
            }
            case CREAR_VIAJE -> {
                ViajeRequest request = mapper.readValue(pending.getParametros(), ViajeRequest.class);
                validar(request);
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                entityManager.refresh(ruta, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshot(ruta));
                viajes.validarCreacion(ruta.getId(), request);
                var created = viajes.crear(ruta.getId(), request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Viaje programado para el " + created.fecha() + " a las " + created.hora() + ".");
            }
            case SOLICITAR_UNIRSE -> {
                SolicitudRequest request = mapper.readValue(pending.getParametros(), SolicitudRequest.class);
                validar(request);
                Viaje viaje = viajeRepository.findById(pending.getRecursoId())
                        .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
                // Same order as route editing: route before trip. Refresh after acquiring locks.
                Ruta ruta = viaje.getRuta();
                entityManager.refresh(ruta, LockModeType.PESSIMISTIC_READ);
                entityManager.refresh(viaje, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshot(viaje));
                // Pure validation also prevents REQUIRES_NEW duplicate notices under these locks.
                solicitudes.validarCreacion(viaje.getId(), request);
                var created = solicitudes.crear(viaje.getId(), request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Solicitud enviada. Queda PENDIENTE de aceptación por el conductor.");
            }
            case REGISTRAR_METODO -> {
                MetodoCompensacionRequest request = mapper.readValue(pending.getParametros(), MetodoCompensacionRequest.class);
                validar(request);
                metodos.validarRegistro(request);
                var created = metodos.registrar(request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Método " + created.tipo() + ": " + created.descripcion() + " registrado.");
            }
        }
        throw new IllegalStateException("Tipo de operación no soportado");
    }

    private OperacionResumen guardar(Usuario usuario, TipoOperacion tipo, Long recursoId,
            Object parameters, JsonNode snapshot, String description) {
        // Only preparation takes an exclusive user lock. It calls no notifying/write business service.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        exigirRol(usuario, tipo);
        if (confirmations.countByUsuarioIdAndEstadoAndExpiraAfter(usuario.getId(),
                EstadoConfirmacion.PENDIENTE, clock.instant()) >= 20)
            throw new BusinessException("Tienes 20 operaciones pendientes. Confirma, cancela o espera su expiración.");
        AgentConversationStore.validateNoSecrets(description);
        AgentConversationStore.validateNoSecrets(snapshot.toString());
        PendingConfirmation pending = new PendingConfirmation();
        pending.setId(UUID.randomUUID());
        pending.setUsuario(usuario);
        pending.setTipo(tipo);
        pending.setEstado(EstadoConfirmacion.PENDIENTE);
        pending.setParametros(mapper.writeValueAsString(parameters));
        pending.setSnapshot(snapshot.toString());
        pending.setResumen(description);
        pending.setRecursoId(recursoId);
        pending.setCreada(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        pending.setExpira(pending.getCreada().plus(TTL));
        confirmations.save(pending);
        ConfirmacionResumen response = resumen(pending);
        return new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null,
                description + " ¿Confirmas esta operación?", response);
    }

    private PendingConfirmation propia(UUID id) {
        Usuario usuario = usuarios.usuarioActual();
        return confirmations.bloquearPropia(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Confirmación no encontrada"));
    }

    private void expirarSiCorresponde(PendingConfirmation pending) {
        if (pending.getEstado() == EstadoConfirmacion.PENDIENTE && !pending.getExpira().isAfter(clock.instant()))
            pending.setEstado(EstadoConfirmacion.EXPIRADA);
    }

    private ConfirmacionResumen resumen(PendingConfirmation pending) {
        return new ConfirmacionResumen(pending.getId(), pending.getTipo(), pending.getEstado(),
                pending.getResumen(), mapper.readTree(pending.getParametros()), pending.getExpira(),
                pending.getResultado() == null ? null : mapper.readValue(pending.getResultado(), OperacionResumen.class));
    }

    private Usuario autorizado(TipoOperacion tipo) {
        Usuario usuario = usuarios.usuarioActual();
        exigirRol(usuario, tipo);
        return usuario;
    }

    private void exigirRol(Usuario usuario, TipoOperacion tipo) {
        Rol requerido = switch (tipo) {
            case CREAR_RUTA, CREAR_VIAJE -> Rol.CONDUCTOR;
            case SOLICITAR_UNIRSE -> Rol.PASAJERO;
            case REGISTRAR_METODO -> null;
        };
        if (requerido != null && usuario.getRolPrincipal() != requerido)
            throw new NoPermitidoException("La operación requiere el rol " + requerido + ".");
    }

    private void validar(Object request) {
        if (request == null) throw new CamposInvalidosException("Faltan los datos de la operación");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new CamposInvalidosException(violations.iterator().next().getMessage());
        String json = mapper.writeValueAsString(request);
        if (json.length() > 8000) throw new CamposInvalidosException("Los datos de la operación son demasiado extensos");
        AgentConversationStore.validateNoSecrets(json);
        if (request instanceof RutaRequest ruta && (ruta.origen().length() > 255
                || (ruta.notaContribucion() != null && ruta.notaContribucion().length() > 255)))
            throw new CamposInvalidosException("El origen y la nota de contribución no pueden superar 255 caracteres");
        if (request instanceof MetodoCompensacionRequest metodo && metodo.descripcion().length() > 255)
            throw new CamposInvalidosException("La descripción no puede superar 255 caracteres");
    }

    private JsonNode snapshot(Ruta ruta) {
        var data = mapper.createObjectNode();
        data.put("rutaId", ruta.getId());
        data.put("conductorId", ruta.getConductor().getId());
        data.put("origen", ruta.getOrigen()); data.put("destino", ruta.getDestino());
        data.put("capacidad", ruta.getCapacidadMaxima());
        data.put("referencias", ruta.getPuntosReferencia());
        data.put("zonas", ruta.getZonasSinParada()); data.put("nota", ruta.getNotaContribucion());
        data.put("estado", ruta.getEstado().name());
        return data;
    }

    private JsonNode snapshot(Viaje viaje) {
        var data = mapper.createObjectNode();
        data.set("ruta", snapshot(viaje.getRuta()));
        data.put("viajeId", viaje.getId()); data.put("fecha", viaje.getFecha().toString());
        data.put("hora", viaje.getHora().toString()); data.put("estado", viaje.getEstado().name());
        return data;
    }

    private void comprobarSnapshot(PendingConfirmation pending, JsonNode actual) {
        // JsonNode numeric subtypes may differ before/after persistence (LongNode versus IntNode).
        // Parse both representations identically; values and all fields are still compared exactly.
        if (!mapper.readTree(pending.getSnapshot()).equals(mapper.readTree(actual.toString())))
            throw new BusinessException("Los datos cambiaron desde la preparación. Cancela esta confirmación y prepara una nueva operación.");
    }

    private String opcional(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }
}
