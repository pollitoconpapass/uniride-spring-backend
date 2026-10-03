package com.uniride.repositories;

import com.uniride.entities.AcuerdoCompensacion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcuerdoCompensacionRepository extends JpaRepository<AcuerdoCompensacion, Long> {

    Optional<AcuerdoCompensacion> findBySolicitudId(Long solicitudId);
}
