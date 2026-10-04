package com.uniride.services;

import com.uniride.dto.MetodoCompensacionRequest;
import com.uniride.dto.MetodoCompensacionRespuesta;
import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.MetodoCompensacionMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetodoCompensacionService {

    private final MetodoCompensacionRepository metodoCompensacionRepository;
    private final MetodoCompensacionMapper metodoCompensacionMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public MetodoCompensacionService(MetodoCompensacionRepository metodoCompensacionRepository,
            MetodoCompensacionMapper metodoCompensacionMapper, UsuarioService usuarioService,
            NotificacionService notificacionService) {
        this.metodoCompensacionRepository = metodoCompensacionRepository;
        this.metodoCompensacionMapper = metodoCompensacionMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional(readOnly = true)
    public List<MetodoCompensacionRespuesta> listar() {
        Usuario usuario = usuarioService.usuarioActual();
        return metodoCompensacionMapper.toListaMetodoCompensacionRespuesta(
                metodoCompensacionRepository.findByUsuarioIdOrderByIdDesc(usuario.getId()));
    }

    @Transactional
    public MetodoCompensacionRespuesta registrar(MetodoCompensacionRequest request) {
        Usuario usuario = usuarioService.usuarioActual();
        String descripcion = request.getDescripcion().trim();

        if (metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
                usuario.getId(), request.getTipo(), descripcion)) {
            throw new BusinessException("Ya tienes un método con el mismo tipo y descripción en tu lista");
        }

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

    @Transactional
    public MetodoCompensacionRespuesta actualizar(Long id, MetodoCompensacionRequest request) {
        Usuario usuario = usuarioService.usuarioActual();
        MetodoCompensacion metodo = metodoCompensacionRepository
                .findByIdAndUsuarioId(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Método de compensación no encontrado"));

        String descripcion = request.getDescripcion().trim();
        if (metodoCompensacionRepository.existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
                usuario.getId(), request.getTipo(), descripcion, id)) {
            throw new BusinessException("Ya tienes un método con el mismo tipo y descripción en tu lista");
        }

        metodoCompensacionMapper.actualizarMetodoCompensacion(request, metodo);
        metodo.setDescripcion(descripcion);
        if (request.getActivo() != null) {
            metodo.setActivo(request.getActivo());
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
        Usuario usuario = usuarioService.usuarioActual();
        MetodoCompensacion metodo = metodoCompensacionRepository
                .findByIdAndUsuarioId(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Método de compensación no encontrado"));

        if (metodoCompensacionRepository.estaAsociadoAViajeConfirmado(id, EstadoSolicitud.ACEPTADA)) {
            throw new BusinessException(
                    "No puedes eliminar este método: está asociado a un viaje confirmado");
        }
        metodoCompensacionRepository.delete(metodo);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                "Método de compensación eliminado",
                "Tu método " + metodo.getTipo() + ": " + metodo.getDescripcion() + " fue eliminado.");

        return Map.of("mensaje", "Método de compensación eliminado correctamente");
    }
}
