package com.uniride.repositories;

import com.uniride.entities.Vehiculo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehiculoRepository extends JpaRepository<Vehiculo, Long> {

    List<Vehiculo> findByConductorId(Long conductorId);

    boolean existsByPlaca(String placa);
}
