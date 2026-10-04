package com.uniride.repositories;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetodoCompensacionRepository extends JpaRepository<MetodoCompensacion, Long> {

    List<MetodoCompensacion> findByUsuarioIdOrderByIdDesc(Long usuarioId);

    Optional<MetodoCompensacion> findByIdAndUsuarioId(Long id, Long usuarioId);

    boolean existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
            Long usuarioId, TipoCompensacion tipo, String descripcion);

    boolean existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
            Long usuarioId, TipoCompensacion tipo, String descripcion, Long id);

    @Query("SELECT COUNT(s) > 0 FROM Solicitud s "
            + "WHERE s.metodoCompensacion.id = :metodoId "
            + "AND s.estado = :estado AND s.viaje.confirmado = true")
    boolean estaAsociadoAViajeConfirmado(@Param("metodoId") Long metodoId,
            @Param("estado") EstadoSolicitud estado);
}
