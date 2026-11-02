package com.uniride.services;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.mappers.BusquedaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.ZoneId;
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
        if (page < 0 || size < 1 || size > 100) {
            throw new CamposInvalidosException("La pagina debe ser >= 0 y el tamaño debe estar entre 1 y 100");
        }
        Usuario usuario = usuarioService.usuarioActual();

        String distrito = null;
        if (cercania) {
            Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Debes completar tu perfil para buscar por cercanía"));
            if (perfil.getDistrito() == null || perfil.getDistrito().isBlank()) {
                throw new CamposInvalidosException("Indica tu distrito en el perfil para buscar por cercania");
            }
            distrito = normalizar(perfil.getDistrito());
        }

        LocalDate hoy = LocalDate.now(ZoneId.systemDefault());
        LocalTime horaActual = LocalTime.now(ZoneId.systemDefault());
        return viajeRepository.buscarViajesRelevantes(
                normalizar(origen), normalizar(destino), normalizar(dia), hora,
                distrito, EstadoViaje.PROGRAMADO, EstadoRuta.ELIMINADA,
                hoy, horaActual, PageRequest.of(page, size))
                .map(busquedaMapper::toBusquedaRespuesta);
    }

    private String normalizar(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim().toLowerCase();
    }
}
