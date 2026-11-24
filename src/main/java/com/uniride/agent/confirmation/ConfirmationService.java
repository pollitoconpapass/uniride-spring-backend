package com.uniride.agent.confirmation;

import com.uniride.agent.AgentConversationStore;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.dto.responses.MetodoCompensacionRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.*;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import com.uniride.services.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The model prepares operations. The authenticated user resolves the same persisted proposal from either channel. */
@Service
@RequiredArgsConstructor
public class ConfirmationService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String SNAPSHOT_OBSOLETO =
            "Los datos cambiaron desde la preparación. Cancela esta confirmación y prepara una nueva operación.";
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
        return prepararViaje(rutaId, request, null);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararViaje(RutaRespuesta esperada, ViajeRequest request) {
        return prepararViaje(esperada.id(), request, esperada);
    }

    private OperacionResumen prepararViaje(Long rutaId, ViajeRequest request, RutaRespuesta esperada) {
        Usuario usuario = autorizado(TipoOperacion.CREAR_VIAJE);
        validar(request);
        Ruta ruta = viajes.validarCreacion(rutaId, request);
        if (esperada != null && !coincideRuta(ruta, esperada))
            throw new ResourceNotFoundException("La ruta elegida cambió.");
        return guardar(usuario, TipoOperacion.CREAR_VIAJE, rutaId, request, snapshot(ruta),
                "Programar un viaje desde " + ruta.getOrigen() + " hacia " + ruta.getDestino()
                        + " el " + request.fecha() + " a las " + request.hora()
                        + ", capacidad " + ruta.getCapacidadMaxima() + ". Referencias: " + ruta.getPuntosReferencia() + ".");
    }

    private boolean coincideRuta(Ruta ruta, RutaRespuesta esperada) {
        return Objects.equals(ruta.getId(), esperada.id())
                && Objects.equals(ruta.getConductor().getId(), esperada.conductorId())
                && Objects.equals(ruta.getOrigen(), esperada.origen())
                && Objects.equals(ruta.getDestino(), esperada.destino())
                && Objects.equals(ruta.getPuntosReferencia(), esperada.puntosReferencia())
                && Objects.equals(ruta.getZonasSinParada(), esperada.zonasSinParada())
                && Objects.equals(ruta.getNotaContribucion(), esperada.notaContribucion())
                && Objects.equals(ruta.getCapacidadMaxima(), esperada.capacidadMaxima())
                && ruta.getEstado() == esperada.estado();
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
    public OperacionResumen prepararEdicionRuta(Long rutaId, String nuevoOrigen, Integer capacidadMaxima,
            String puntosReferencia, String zonasSinParada, String notaContribucion) {
        Usuario usuario = autorizado(TipoOperacion.EDITAR_RUTA);
        Ruta ruta = rutaPropia(rutaId, usuario.getId());
        if (ruta.getEstado() == EstadoRuta.ELIMINADA)
            throw new BusinessException("No se puede editar una ruta eliminada");
        RutaRequest request = new RutaRequest(
                nuevoOrigen == null ? ruta.getOrigen() : nuevoOrigen,
                capacidadMaxima == null ? ruta.getCapacidadMaxima() : capacidadMaxima,
                puntosReferencia == null ? ruta.getPuntosReferencia() : puntosReferencia,
                zonasSinParada == null ? ruta.getZonasSinParada() : zonasSinParada,
                notaContribucion == null ? ruta.getNotaContribucion() : notaContribucion);
        validar(request);

        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(ruta.getOrigen(), request.origen())) cambios.add("origen " + request.origen());
        if (ruta.getCapacidadMaxima() != request.capacidadMaxima())
            cambios.add("capacidad " + request.capacidadMaxima());
        if (!Objects.equals(ruta.getPuntosReferencia(), request.puntosReferencia()))
            cambios.add("puntos de referencia " + request.puntosReferencia());
        if (!Objects.equals(ruta.getZonasSinParada(), request.zonasSinParada()))
            cambios.add("zonas sin parada " + (request.zonasSinParada() == null ? "vacías" : request.zonasSinParada()));
        if (!Objects.equals(ruta.getNotaContribucion(), request.notaContribucion()))
            cambios.add("nota de contribución " + (request.notaContribucion() == null ? "vacía" : request.notaContribucion()));
        if (cambios.isEmpty())
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "La ruta ya tiene los valores solicitados.");

        return guardar(usuario, TipoOperacion.EDITAR_RUTA, rutaId, request, snapshot(ruta),
                "Actualizar tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + ": "
                        + String.join(", ", cambios) + ". Los pasajeros aceptados serán notificados.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEliminacionRuta(Long rutaId) {
        Usuario usuario = autorizado(TipoOperacion.ELIMINAR_RUTA);
        Ruta ruta = rutaPropia(rutaId, usuario.getId());
        if (ruta.getEstado() == EstadoRuta.ELIMINADA)
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "La ruta ya estaba eliminada.");
        return guardar(usuario, TipoOperacion.ELIMINAR_RUTA, rutaId, mapper.createObjectNode(), snapshot(ruta),
                "Eliminar tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino()
                        + ". Los viajes programados futuros se cancelarán y el historial se conserva.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararReprogramacionViaje(Long viajeId, LocalDate fecha, LocalTime hora) {
        Usuario usuario = autorizado(TipoOperacion.REPROGRAMAR_VIAJE);
        Viaje viaje = viajeRepository.findByIdAndRutaConductorId(viajeId, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
        if (viaje.getRuta().getEstado() == EstadoRuta.ELIMINADA)
            throw new BusinessException("No se puede reprogramar un viaje de una ruta eliminada");
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO)
            throw new BusinessException("Solo se pueden reprogramar viajes en estado PROGRAMADO");
        ViajeRequest request = new ViajeRequest(fecha, hora);
        validar(request);
        if (!LocalDateTime.of(fecha, hora).isAfter(LocalDateTime.now(clock)))
            throw new CamposInvalidosException("La nueva fecha y hora deben ser futuras");
        if (viaje.getFecha().equals(fecha) && viaje.getHora().equals(hora))
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "El horario no cambió.");
        viajes.validarNuevaSalida(request);
        return guardar(usuario, TipoOperacion.REPROGRAMAR_VIAJE, viajeId, request, snapshot(viaje),
                "Reprogramar tu viaje del " + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora()
                        + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino()
                        + ") para el " + fecha + " a las " + hora + ". Los pasajeros aceptados serán notificados.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararCancelacionSolicitudes(List<SolicitudResumen> solicitudes) {
        Usuario usuario = autorizado(TipoOperacion.CANCELAR_SOLICITUD);
        if (solicitudes == null || solicitudes.isEmpty())
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No tienes solicitudes pendientes que cancelar.");
        List<Long> ids = solicitudes.stream().map(SolicitudResumen::id).toList();
        String detalle = String.join("; ", solicitudes.stream()
                .map(s -> s.origen() + " → " + s.destino() + " (" + s.fecha() + " " + s.hora() + ")").toList());
        return guardar(usuario, TipoOperacion.CANCELAR_SOLICITUD, null, ids, mapper.createObjectNode(),
                "Cancelar " + (ids.size() == 1 ? "tu solicitud pendiente: " : ids.size() + " solicitudes pendientes: ")
                        + detalle + ".");
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
    public OperacionResumen prepararActualizacionMetodo(TipoCompensacion tipoActual, String descripcionActual,
            TipoCompensacion nuevoTipo, String nuevaDescripcion) {
        return prepararGestionMetodo(TipoOperacion.ACTUALIZAR_METODO, tipoActual, descripcionActual,
                new MetodoCompensacionRequest(nuevoTipo, nuevaDescripcion, null));
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEstadoMetodo(TipoCompensacion tipoActual, String descripcionActual, Boolean activo) {
        return prepararGestionMetodo(TipoOperacion.CAMBIAR_ESTADO_METODO, tipoActual, descripcionActual,
                new MetodoCompensacionRequest(null, null, activo));
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEliminacionMetodo(TipoCompensacion tipoActual, String descripcionActual) {
        return prepararGestionMetodo(TipoOperacion.ELIMINAR_METODO, tipoActual, descripcionActual, null);
    }

    private OperacionResumen prepararGestionMetodo(TipoOperacion tipo, TipoCompensacion tipoActual,
            String descripcionActual, MetodoCompensacionRequest cambios) {
        Usuario usuario = autorizado(tipo);
        if (tipoActual == null && (descripcionActual == null || descripcionActual.isBlank())) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el tipo o la descripción del método de compensación.");
        }
        if (tipo == TipoOperacion.ACTUALIZAR_METODO && cambios.tipo() == null && cambios.descripcion() == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "¿Qué quieres cambiar en tu método de compensación? Indica el nuevo tipo o la nueva descripción.");
        }
        if (tipo == TipoOperacion.CAMBIAR_ESTADO_METODO && cambios.activo() == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "¿Quieres activar o desactivar el método de compensación?");
        }

        // Serialize selection and snapshot with both REST and confirmed method changes.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        exigirRol(usuario, tipo);
        var coincidencias = metodos.buscarCoincidenciasPropias(tipoActual, descripcionActual);
        if (coincidencias.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un método de compensación propio que coincida.");
        }
        if (coincidencias.size() > 1) {
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Tienes varios métodos de compensación que coinciden. Indica el tipo y la descripción exacta del que deseas cambiar.");
        }
        MetodoCompensacionRespuesta actual = coincidencias.getFirst();
        JsonNode snapshot = snapshotMetodo(actual.id(), usuario.getId(), actual.tipo(), actual.descripcion(), actual.activo());
        String anterior = describirMetodo(actual.tipo(), actual.descripcion(), actual.activo());

        if (tipo == TipoOperacion.ELIMINAR_METODO) {
            metodos.validarEliminacion(actual.id());
            return guardarBajoBloqueo(usuario, tipo, actual.id(), mapper.createObjectNode(), snapshot,
                    "Eliminar tu método " + anterior + ".");
        }

        var request = new MetodoCompensacionRequest(cambios.tipo() == null ? actual.tipo() : cambios.tipo(),
                cambios.descripcion() == null ? actual.descripcion() : cambios.descripcion().trim(),
                cambios.activo() == null ? actual.activo() : cambios.activo());
        validar(request);
        if (request.tipo() == actual.tipo() && request.descripcion().equals(actual.descripcion())
                && request.activo() == actual.activo()) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null,
                    "Tu método de compensación ya tiene los valores solicitados.");
        }
        metodos.validarActualizacion(actual.id(), request);
        String accion = tipo == TipoOperacion.ACTUALIZAR_METODO ? "Actualizar" : request.activo() ? "Activar" : "Desactivar";
        String posterior = describirMetodo(request.tipo(), request.descripcion(), request.activo());
        return guardarBajoBloqueo(usuario, tipo, actual.id(), request, snapshot,
                accion + " tu método. Antes era " + anterior + ". Quedará como " + posterior + ".");
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen obtener(UUID id) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen cancelar(UUID id) {
        return cancelar(propia(id));
    }

    private ConfirmacionResumen cancelar(PendingConfirmation pending) {
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA)
            throw new BusinessException("La operación ya fue ejecutada; no se puede cancelar la confirmación.");
        if (pending.getEstado() == EstadoConfirmacion.PENDIENTE) pending.setEstado(EstadoConfirmacion.CANCELADA);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen confirmar(UUID id) {
        return confirmar(propia(id));
    }

    /** Reads the previous state and resolves under the same row lock, including when the app races the chat. */
    @Transactional(timeout = 10)
    public ResolucionConfirmacion resolverDesdeChat(UUID id, boolean confirmar) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        return switch (pending.getEstado()) {
            case EJECUTADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.YA_EJECUTADA, resumen(pending));
            case CANCELADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.YA_CANCELADA, resumen(pending));
            case EXPIRADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.EXPIRADA, resumen(pending));
            case PENDIENTE -> {
                ConfirmacionResumen resultado = confirmar ? confirmar(pending) : cancelar(pending);
                var estado = switch (resultado.estado()) {
                    case EJECUTADA -> ResolucionConfirmacion.Estado.EJECUTADA;
                    case CANCELADA -> ResolucionConfirmacion.Estado.CANCELADA;
                    case EXPIRADA -> ResolucionConfirmacion.Estado.EXPIRADA;
                    case PENDIENTE -> throw new IllegalStateException("La decisión no resolvió la confirmación");
                };
                yield new ResolucionConfirmacion(estado, resultado);
            }
        };
    }

    private ConfirmacionResumen confirmar(PendingConfirmation pending) {
        // A replay returns the committed receipt, without invoking any business service.
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA) return resumen(pending);
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EXPIRADA) return resumen(pending);
        if (pending.getEstado() == EstadoConfirmacion.CANCELADA)
            throw new BusinessException("La confirmación fue cancelada. Prepara una nueva operación.");

        Usuario usuario = usuarios.usuarioActual();
        // FOR SHARE keeps the current role stable while remaining compatible with notification FKs.
        // Compensation changes share an exclusive owner lock with REST before any method lock.
        // Acquire it directly to avoid lock upgrades and serialize checks across different methods.
        entityManager.refresh(usuario, pending.getTipo() == TipoOperacion.REGISTRAR_METODO
                || pending.getTipo().modificaMetodoExistente()
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
            case EDITAR_RUTA -> {
                RutaRequest request = mapper.readValue(pending.getParametros(), RutaRequest.class);
                validar(request);
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                comprobarSnapshot(pending, snapshot(ruta));
                var actualizada = rutas.actualizar(ruta.getId(), request, true);
                return new OperacionResumen(EstadoOperacion.EXITO, actualizada.id(),
                        "Ruta " + actualizada.origen() + " → " + actualizada.destino() + " actualizada.");
            }
            case ELIMINAR_RUTA -> {
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                comprobarSnapshot(pending, snapshot(ruta));
                String detalle = "Ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + " eliminada.";
                rutas.eliminar(ruta.getId(), true);
                return new OperacionResumen(EstadoOperacion.EXITO, ruta.getId(), detalle);
            }
            case REPROGRAMAR_VIAJE -> {
                ViajeRequest request = mapper.readValue(pending.getParametros(), ViajeRequest.class);
                validar(request);
                Viaje viaje = viajeRepository.bloquearParaConductor(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
                comprobarSnapshot(pending, snapshot(viaje));
                var actualizado = viajes.actualizar(viaje.getId(), request, true);
                return new OperacionResumen(EstadoOperacion.EXITO, actualizado.id(),
                        "Viaje reprogramado para el " + actualizado.fecha() + " a las " + actualizado.hora() + ".");
            }
            case CANCELAR_SOLICITUD -> {
                Long[] ids = mapper.readValue(pending.getParametros(), Long[].class);
                for (Long id : ids) solicitudes.cancelar(id);
                return new OperacionResumen(EstadoOperacion.EXITO, null,
                        ids.length == 1 ? "Cancelé tu solicitud pendiente." : "Cancelé " + ids.length + " solicitudes pendientes.");
            }
            case REGISTRAR_METODO -> {
                MetodoCompensacionRequest request = mapper.readValue(pending.getParametros(), MetodoCompensacionRequest.class);
                validar(request);
                metodos.validarRegistro(request);
                var created = metodos.registrar(request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Método " + created.tipo() + ": " + created.descripcion() + " registrado.");
            }
            case ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO -> {
                return ejecutarGestionMetodo(pending);
            }
        }
        throw new IllegalStateException("Tipo de operación no soportado");
    }

    private OperacionResumen ejecutarGestionMetodo(PendingConfirmation pending) {
        MetodoCompensacion metodo = metodos.bloquearMetodoPropio(pending.getRecursoId())
                .orElseThrow(() -> new BusinessException(SNAPSHOT_OBSOLETO));
        comprobarSnapshot(pending, snapshotMetodo(metodo.getId(), metodo.getUsuario().getId(), metodo.getTipo(),
                metodo.getDescripcion(), metodo.isActivo()));

        if (pending.getTipo() == TipoOperacion.ELIMINAR_METODO) {
            var eliminado = metodos.eliminar(metodo.getId());
            return new OperacionResumen(EstadoOperacion.EXITO, metodo.getId(), eliminado.get("mensaje"));
        }
        var request = mapper.readValue(pending.getParametros(), MetodoCompensacionRequest.class);
        validar(request);
        var actualizado = metodos.actualizar(metodo.getId(), request);
        return new OperacionResumen(EstadoOperacion.EXITO, actualizado.id(),
                "Método " + describirMetodo(actualizado.tipo(), actualizado.descripcion(), actualizado.activo()) + " actualizado.");
    }

    private OperacionResumen guardar(Usuario usuario, TipoOperacion tipo, Long recursoId,
            Object parameters, JsonNode snapshot, String description) {
        // Only preparation takes an exclusive user lock. It calls no notifying/write business service.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        exigirRol(usuario, tipo);
        return guardarBajoBloqueo(usuario, tipo, recursoId, parameters, snapshot, description);
    }

    private OperacionResumen guardarBajoBloqueo(Usuario usuario, TipoOperacion tipo, Long recursoId,
            Object parameters, JsonNode snapshot, String description) {
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
        IntentoPreparacion.marcarPersistencia();
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

    private Ruta rutaPropia(Long id, Long usuarioId) {
        return rutaRepository.findById(id)
                .filter(ruta -> ruta.getConductor().getId().equals(usuarioId))
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
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
            case CREAR_RUTA, CREAR_VIAJE, EDITAR_RUTA, ELIMINAR_RUTA, REPROGRAMAR_VIAJE -> Rol.CONDUCTOR;
            case SOLICITAR_UNIRSE, CANCELAR_SOLICITUD -> Rol.PASAJERO;
            case REGISTRAR_METODO -> null;
            case ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO -> {
                if (usuario.getRolPrincipal() == null) throw new NoPermitidoException("La operación requiere un rol activo.");
                yield null;
            }
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

    private JsonNode snapshotMetodo(Long id, Long usuarioId, TipoCompensacion tipo, String descripcion, boolean activo) {
        return mapper.createObjectNode().put("metodoId", id).put("usuarioId", usuarioId).put("tipo", tipo.name())
                .put("descripcion", descripcion).put("activo", activo);
    }

    private String describirMetodo(TipoCompensacion tipo, String descripcion, boolean activo) {
        return tipo + " «" + descripcion + "» (" + (activo ? "activo" : "inactivo") + ")";
    }

    private void comprobarSnapshot(PendingConfirmation pending, JsonNode actual) {
        // JsonNode numeric subtypes may differ before/after persistence (LongNode versus IntNode).
        // Parse both representations identically; values and all fields are still compared exactly.
        if (!mapper.readTree(pending.getSnapshot()).equals(mapper.readTree(actual.toString())))
            throw new BusinessException(SNAPSHOT_OBSOLETO);
    }

    private String opcional(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }
}
