package com.uniride.repositories;

import com.uniride.entities.Penalidad;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenalidadRepository extends JpaRepository<Penalidad, Long> {

    long countByUsuarioId(Long usuarioId);

    long countByUsuarioIdAndViajeIsNotNull(Long usuarioId);

    boolean existsByViajeIdAndUsuarioId(Long viajeId, Long usuarioId);

    // Cierre transversal: impedir el borrado fisico de viajes con sanciones.
    boolean existsByViajeId(Long viajeId);

    List<Penalidad> findByViajeId(Long viajeId);

    List<Penalidad> findByUsuarioId(Long usuarioId);

    // US13: cargar en lote las penalidades del pasajero autenticado en los viajes de la pagina.
    List<Penalidad> findByUsuarioIdAndViajeIdIn(Long usuarioId, Collection<Long> viajeIds);
}
