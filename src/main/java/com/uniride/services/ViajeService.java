package com.uniride.services;

import com.uniride.dto.ViajeRequest;
import com.uniride.dto.ViajeRespuesta;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ViajeService {

    private final ViajeRepository viajeRepository;
    private final RutaRepository rutaRepository;
    private final ViajeMapper viajeMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public ViajeService(ViajeRepository viajeRepository, RutaRepository rutaRepository,
            ViajeMapper viajeMapper, UsuarioService usuarioService,
            NotificacionService notificacionService) {
        this.viajeRepository = viajeRepository;
        this.rutaRepository = rutaRepository;
        this.viajeMapper = viajeMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional
    public ViajeRespuesta crear(Long rutaId, ViajeRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = buscarRutaPropia(rutaId, conductor);

        if (request.getFecha().isBefore(LocalDate.now())) {
            throw new CamposInvalidosException("La fecha del viaje no puede ser en el pasado");
        }

        Viaje viaje = viajeMapper.toViaje(request);
        viaje.setRuta(ruta);
        viaje.setDia(diaDeLaSemana(request.getFecha()));
        viaje.setEstado(EstadoViaje.PROGRAMADO);
        Viaje guardado = viajeRepository.save(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje creado",
                "Se creó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + " en tu ruta "
                        + ruta.getOrigen() + " → " + ruta.getDestino() + ".");

        return viajeMapper.toViajeRespuesta(guardado);
    }

    @Transactional(readOnly = true)
    public Page<ViajeRespuesta> listarPorRuta(Long rutaId, int page, int size) {
        Usuario conductor = usuarioService.usuarioActual();
        buscarRutaPropia(rutaId, conductor);
        return viajeRepository.findByRutaIdOrderByFechaAscHoraAsc(rutaId, PageRequest.of(page, size))
                .map(viajeMapper::toViajeRespuesta);
    }

    @Transactional
    public void eliminar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.findByIdAndRutaConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        viajeRepository.delete(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje eliminado",
                "Se eliminó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");
    }

    private Ruta buscarRutaPropia(Long rutaId, Usuario conductor) {
        return rutaRepository.findByIdAndConductorId(rutaId, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
    }

    private String diaDeLaSemana(LocalDate fecha) {
        return switch (fecha.getDayOfWeek()) {
            case MONDAY -> "Lunes";
            case TUESDAY -> "Martes";
            case WEDNESDAY -> "Miercoles";
            case THURSDAY -> "Jueves";
            case FRIDAY -> "Viernes";
            case SATURDAY -> "Sabado";
            case SUNDAY -> "Domingo";
        };
    }
}
