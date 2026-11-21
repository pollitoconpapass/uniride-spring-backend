package com.uniride.agent.confirmation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ConfirmationRepository extends JpaRepository<PendingConfirmation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PendingConfirmation c where c.id = :id and c.usuario.id = :usuario")
    Optional<PendingConfirmation> bloquearPropia(@Param("id") UUID id, @Param("usuario") Long usuario);

    long countByUsuarioIdAndEstadoAndExpiraAfter(Long usuarioId, EstadoConfirmacion estado, Instant ahora);

}
