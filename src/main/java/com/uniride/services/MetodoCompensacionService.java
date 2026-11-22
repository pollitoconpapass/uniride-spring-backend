package com.uniride.services;

import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.dto.responses.MetodoCompensacionRespuesta;
import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoCompensacion;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.MetodoCompensacionMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetodoCompensacionService {

    private static final PageRequest LIMITE_COINCIDENCIAS = PageRequest.of(0, 2);

    private final MetodoCompensacionRepository metodoCompensacionRepository;
    private final MetodoCompensacionMapper metodoCompensacionMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;
    private final EntityManager entityManager;

    public MetodoCompensacionService(MetodoCompensacionRepository metodoCompensacionRepository,
            MetodoCompensacionMapper metodoCompensacionMapper, UsuarioService usuarioService,
            NotificacionService notificacionService, EntityManager entityManager) {
        this.metodoCompensacionRepository = metodoCompensacionRepository;
        this.metodoCompensacionMapper = metodoCompensacionMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<MetodoCompensacionRespuesta> listar() {
        Usuario usuario = usuarioService.usuarioActual();
        return metodoCompensacionMapper.toListaMetodoCompensacionRespuesta(
                metodoCompensacionRepository.findByUsuarioIdOrderByIdDesc(usuario.getId()));
    }

    @Transactional
    public MetodoCompensacionRespuesta registrar(MetodoCompensacionRequest request) {
        Usuario usuario = bloquearUsuarioActual();
        String descripcion = request.descripcion().trim();
        validarRegistro(usuario, request);

        MetodoCompensacion metodo = metodoCompensacionMapper.toMetodoCompensacion(request);
        metodo.setUsuario(usuario);
        metodo.setDescripcion(descripcion);
        metodo.setActivo(true);
        MetodoCompensacion guardado = metodoCompensacionRepository.save(metodo);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                "Método de compensación registrado",
                "Registraste tu método " + guardado.getTipo() + ": " + guardado.getDescripcion() + ".");

        return metodoCompensacionMapper.toMetodoCompensacionRespuesta(guardado);
    }

    @Transactional(readOnly = true)
    public void validarRegistro(MetodoCompensacionRequest request) {
        validarRegistro(usuarioService.usuarioActual(), request);
    }

    private void validarRegistro(Usuario usuario, MetodoCompensacionRequest request) {
        if (metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                usuario.getId(), request.tipo(), request.descripcion().trim())) {
            throw new BusinessException("Ya tienes un método con el mismo tipo y descripción en tu lista");
        }
    }

    @Transactional
    public MetodoCompensacionRespuesta actualizar(Long id, MetodoCompensacionRequest request) {
        Usuario usuario = bloquearUsuarioActual();
        MetodoCompensacion metodo = bloquearMetodoPropio(id, usuario.getId())
                .orElseThrow(MetodoCompensacionService::metodoNoEncontrado);

        String descripcion = request.descripcion().trim();
        validarDuplicadoActualizacion(id, usuario.getId(), request);

        metodoCompensacionMapper.actualizarMetodoCompensacion(request, metodo);
        metodo.setDescripcion(descripcion);
        if (request.activo() != null) {
            metodo.setActivo(request.activo());
        }
        MetodoCompensacion actualizado = metodoCompensacionRepository.save(metodo);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                "Método de compensación actualizado",
                "Tu método " + actualizado.getTipo() + ": " + actualizado.getDescripcion()
                        + " fue actualizado.");

        return metodoCompensacionMapper.toMetodoCompensacionRespuesta(actualizado);
    }

    @Transactional
    public Map<String, String> eliminar(Long id) {
        Usuario usuario = bloquearUsuarioActual();
        MetodoCompensacion metodo = bloquearMetodoPropio(id, usuario.getId())
                .orElseThrow(MetodoCompensacionService::metodoNoEncontrado);

        validarSinSolicitudes(id);
        metodoCompensacionRepository.delete(metodo);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                "Método de compensación eliminado",
                "Tu método " + metodo.getTipo() + ": " + metodo.getDescripcion() + " fue eliminado.");

        return Map.of("mensaje", "Método de compensación eliminado correctamente");
    }

    @Transactional(readOnly = true)
    public List<MetodoCompensacionRespuesta> buscarCoincidenciasPropias(TipoCompensacion tipo, String descripcion) {
        Long usuarioId = usuarioService.usuarioActual().getId();
        String texto = descripcion == null || descripcion.isBlank() ? null : descripcion.strip();
        if (tipo == null && texto == null) {
            throw new CamposInvalidosException("Indica el tipo o la descripción del método de compensación.");
        }
        if (texto != null && texto.length() > 255) {
            throw new CamposInvalidosException("La descripción no puede superar 255 caracteres");
        }
        if (texto == null) {
            return metodoCompensacionMapper.toListaMetodoCompensacionRespuesta(metodoCompensacionRepository
                    .findByUsuarioIdAndTipoOrderByIdAsc(usuarioId, tipo, LIMITE_COINCIDENCIAS));
        }
        var coincidencias = metodoCompensacionRepository.buscarCoincidenciasPropias(
                usuarioId, tipo, texto, true, LIMITE_COINCIDENCIAS);
        if (coincidencias.isEmpty()) {
            coincidencias = metodoCompensacionRepository.buscarCoincidenciasPropias(
                    usuarioId, tipo, texto, false, LIMITE_COINCIDENCIAS);
        }
        return metodoCompensacionMapper.toListaMetodoCompensacionRespuesta(coincidencias);
    }

    @Transactional(readOnly = true)
    public void validarActualizacion(Long id, MetodoCompensacionRequest request) {
        Long usuarioId = usuarioService.usuarioActual().getId();
        metodoCompensacionRepository.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(MetodoCompensacionService::metodoNoEncontrado);
        validarDuplicadoActualizacion(id, usuarioId, request);
    }

    @Transactional(readOnly = true)
    public void validarEliminacion(Long id) {
        Long usuarioId = usuarioService.usuarioActual().getId();
        metodoCompensacionRepository.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(MetodoCompensacionService::metodoNoEncontrado);
        validarSinSolicitudes(id);
    }

    /** Confirmation holds the user lock before locking the method. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MetodoCompensacion> bloquearMetodoPropio(Long id) {
        return bloquearMetodoPropio(id, usuarioService.usuarioActual().getId());
    }

    private Usuario bloquearUsuarioActual() {
        Usuario usuario = usuarioService.usuarioActual();
        // Serialize duplicate checks across all method writes, including REST endpoints.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        return usuario;
    }

    private Optional<MetodoCompensacion> bloquearMetodoPropio(Long id, Long usuarioId) {
        return metodoCompensacionRepository.bloquearPropio(id, usuarioId).map(metodo -> {
            // A managed entity may have been loaded before the row lock was acquired.
            entityManager.refresh(metodo);
            return metodo;
        });
    }

    private void validarDuplicadoActualizacion(Long id, Long usuarioId, MetodoCompensacionRequest request) {
        if (metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
                usuarioId, request.tipo(), request.descripcion().trim(), id)) {
            throw new BusinessException("Ya tienes un método con el mismo tipo y descripción en tu lista");
        }
    }

    private void validarSinSolicitudes(Long id) {
        if (metodoCompensacionRepository.estaAsociadoACualquierSolicitud(id)) {
            throw new BusinessException(
                    "No puedes eliminar este método porque tiene solicitudes asociadas. "
                            + "Puedes desactivarlo para conservar el historial.");
        }
    }

    private static ResourceNotFoundException metodoNoEncontrado() {
        return new ResourceNotFoundException("Método de compensación no encontrado");
    }
}
