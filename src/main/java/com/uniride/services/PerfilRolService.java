package com.uniride.services;

import com.uniride.entities.Conductor;
import com.uniride.entities.Pasajero;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.repositories.ConductorRepository;
import com.uniride.repositories.PasajeroRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerfilRolService {

    private final ConductorRepository conductorRepository;
    private final PasajeroRepository pasajeroRepository;

    public PerfilRolService(ConductorRepository conductorRepository,
            PasajeroRepository pasajeroRepository) {
        this.conductorRepository = conductorRepository;
        this.pasajeroRepository = pasajeroRepository;
    }

    /**
     * Crea la especializacion si no existe. Cambiar el rol activo no borra el otro perfil.
     * Debe ejecutarse dentro de la misma transaccion que registra o modifica el usuario.
     */
    @Transactional
    public void asegurarPerfil(Usuario usuario, Rol rol) {
        if (rol == null) {
            return;
        }
        if (usuario == null || usuario.getId() == null) {
            throw new IllegalArgumentException("El usuario debe estar persistido antes de crear su perfil de rol");
        }
        switch (rol) {
            case CONDUCTOR -> {
                if (!conductorRepository.existsById(usuario.getId())) {
                    conductorRepository.save(new Conductor(usuario));
                }
            }
            case PASAJERO -> {
                if (!pasajeroRepository.existsById(usuario.getId())) {
                    pasajeroRepository.save(new Pasajero(usuario));
                }
            }
        }
    }
}
