package com.uniride.repositories;

import com.uniride.entities.CorreoPendiente;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CorreoPendienteRepository extends JpaRepository<CorreoPendiente, Long> {
    @Query("SELECT c.id FROM CorreoPendiente c WHERE c.enviado IS NULL AND c.descartado IS NULL "
            + "AND c.siguienteIntento <= :ahora ORDER BY c.id")
    List<Long> pendientes(@Param("ahora") Instant ahora, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CorreoPendiente c WHERE c.id = :id")
    Optional<CorreoPendiente> bloquear(@Param("id") Long id);
}
