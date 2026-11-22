package com.uniride.repositories;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetodoCompensacionRepository extends JpaRepository<MetodoCompensacion, Long> {

    List<MetodoCompensacion> findByUsuarioIdOrderByIdDesc(Long usuarioId);

    org.springframework.data.domain.Page<MetodoCompensacion> findByUsuarioIdOrderByIdDesc(
            Long usuarioId, org.springframework.data.domain.Pageable pageable);

    Optional<MetodoCompensacion> findByIdAndUsuarioId(Long id, Long usuarioId);

    List<MetodoCompensacion> findByUsuarioIdAndTipoOrderByIdAsc(Long usuarioId, TipoCompensacion tipo, Pageable pagina);

    // PostgreSQL FOR UPDATE also serializes this row with foreign-key inserts from solicitudes.
    @Query(value = "SELECT m.* FROM metodos_compensacion m "
            + "WHERE m.id = :id AND m.usuario_id = :usuarioId FOR UPDATE", nativeQuery = true)
    Optional<MetodoCompensacion> bloquearPropio(@Param("id") Long id, @Param("usuarioId") Long usuarioId);

    @Query("""
            SELECT m FROM MetodoCompensacion m
            WHERE m.usuario.id = :usuarioId AND (:tipo IS NULL OR m.tipo = :tipo)
            AND ((:exacta = true AND LOWER(m.descripcion) = LOWER(:descripcion))
                OR (:exacta = false AND LOCATE(LOWER(:descripcion), LOWER(m.descripcion)) > 0))
            ORDER BY m.id
            """)
    List<MetodoCompensacion> buscarCoincidenciasPropias(@Param("usuarioId") Long usuarioId,
            @Param("tipo") TipoCompensacion tipo, @Param("descripcion") String descripcion,
            @Param("exacta") boolean exacta, Pageable pagina);

    boolean existsByUsuarioIdAndTipoAndDescripcionIgnoreCase(
            Long usuarioId, TipoCompensacion tipo, String descripcion);

    boolean existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
            Long usuarioId, TipoCompensacion tipo, String descripcion, Long id);

    @Query("SELECT COUNT(s) > 0 FROM Solicitud s WHERE s.metodoCompensacion.id = :metodoId")
    boolean estaAsociadoACualquierSolicitud(@Param("metodoId") Long metodoId);

    @Query("SELECT COUNT(s) > 0 FROM Solicitud s "
            + "WHERE s.metodoCompensacion.id = :metodoId "
            + "AND s.estado = :estado AND s.viaje.confirmado = true")
    boolean estaAsociadoAViajeConfirmado(@Param("metodoId") Long metodoId,
            @Param("estado") EstadoSolicitud estado);
}
