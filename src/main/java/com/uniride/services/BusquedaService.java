package com.uniride.services;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.BusquedaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusquedaService {

    private final ViajeRepository viajeRepository;
    private final PerfilRepository perfilRepository;
    private final UsuarioService usuarioService;
    private final BusquedaMapper busquedaMapper;

    public BusquedaService(ViajeRepository viajeRepository, PerfilRepository perfilRepository,
            UsuarioService usuarioService, BusquedaMapper busquedaMapper) {
        this.viajeRepository = viajeRepository;
        this.perfilRepository = perfilRepository;
        this.usuarioService = usuarioService;
        this.busquedaMapper = busquedaMapper;
    }

    @Transactional(readOnly = true)
    public Page<BusquedaRespuesta> buscar(String origen, String destino, String dia,
            LocalTime hora, boolean cercania, int page, int size) {
        Usuario usuario = usuarioService.usuarioActual();

        String distrito = null;
        if (cercania) {
            Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Debes completar tu perfil para buscar por cercanía"));
            distrito = "%" + perfil.getDistrito().trim().toLowerCase() + "%";
        }

        return viajeRepository.buscarViajes(
                normalizar(origen), normalizar(destino), normalizar(dia), hora,
                distrito, EstadoViaje.PROGRAMADO,
                PageRequest.of(page, size))
                .map(busquedaMapper::toBusquedaRespuesta);
    }

    private String normalizar(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim().toLowerCase();
    }
}
