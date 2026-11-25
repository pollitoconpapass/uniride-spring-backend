package com.uniride.repositories;

import com.uniride.dto.responses.AvisoCancelacionRespuesta;
import com.uniride.entities.Notificacion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    List<Notificacion> findByUsuarioIdOrderByFechaEnvioDesc(Long usuarioId);

    long countByUsuarioIdAndLeidaFalse(Long usuarioId);

    @Query("""
            SELECT new com.uniride.dto.responses.AvisoCancelacionRespuesta(
                CASE WHEN n.usuario.id = r.conductor.id THEN true ELSE false END,
                n.tipo, c.asunto, COUNT(n.id), COUNT(c.id), COUNT(c.enviado), COUNT(c.descartado),
                SUM(CASE WHEN c.enviado IS NULL AND c.descartado IS NULL AND c.intentos > 0 THEN 1 ELSE 0 END))
            FROM Notificacion n JOIN n.viajeCancelado v JOIN v.ruta r
            LEFT JOIN CorreoPendiente c ON c.notificacionId = n.id
            WHERE v.id = :viajeId AND r.conductor.id = :conductorId
            GROUP BY CASE WHEN n.usuario.id = r.conductor.id THEN true ELSE false END, n.tipo, c.asunto
            """)
    List<AvisoCancelacionRespuesta> estadoAvisosCancelacion(Long viajeId, Long conductorId);
}
