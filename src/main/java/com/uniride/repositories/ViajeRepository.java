package com.uniride.repositories;

import com.uniride.entities.Viaje;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockModeType;
import com.uniride.enums.EstadoViaje;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViajeRepository extends JpaRepository<Viaje, Long> {

    Page<Viaje> findByRutaIdOrderByFechaAscHoraAsc(Long rutaId, Pageable pageable);

    Optional<Viaje> findByIdAndRutaConductorId(Long id, Long conductorId);

    // 11.2: bloquear la fila del viaje (SELECT ... FOR UPDATE en PostgreSQL)
    // para serializar las aceptaciones que compiten por los mismos cupos.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Viaje v WHERE v.id = :viajeId")
    Optional<Viaje> bloquearParaAceptar(@Param("viajeId") Long viajeId);

    boolean existsByRutaId(Long rutaId);

    List<Viaje> findByRutaId(Long rutaId);

    // US09: una sola consulta para calcular el estado de las rutas del conductor.
    @Query("SELECT v FROM Viaje v WHERE v.ruta.conductor.id = :conductorId")
    List<Viaje> buscarTodosPorConductor(@Param("conductorId") Long conductorId);

    long countByRutaConductorIdAndEstado(Long conductorId, EstadoViaje estado);

    @Query("SELECT v.dia, COUNT(v) FROM Viaje v "
            + "WHERE v.ruta.conductor.id = :conductorId AND v.estado = :estado "
            + "GROUP BY v.dia")
    List<Object[]> frecuenciaPorDiaComoConductor(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoViaje estado);

    @Query("SELECT r.origen, r.destino, COUNT(v) FROM Viaje v JOIN v.ruta r "
            + "WHERE r.conductor.id = :conductorId AND v.estado = :estado "
            + "GROUP BY r.origen, r.destino ORDER BY COUNT(v) DESC")
    List<Object[]> rankingRutasComoConductor(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoViaje estado);

    @Query("SELECT r.origen, r.destino, COUNT(p) FROM Penalidad p JOIN p.viaje v JOIN v.ruta r "
            + "WHERE r.conductor.id = :conductorId "
            + "GROUP BY r.origen, r.destino")
    List<Object[]> penalidadesPorRutaComoConductor(@Param("conductorId") Long conductorId);

    /**
     * US10: filtra y clasifica por relevancia ANTES de aplicar la paginacion.
     * Primero van las coincidencias exactas de origen/destino, luego las parciales,
     * a igualdad de relevancia salen los viajes mas proximos.
     * No se muestran viajes llenos, rutas eliminadas o salidas ya ocurridas.
     * Se preserva buscarViajes() para los consumidores y tests existentes.
     */
    @Query("SELECT v FROM Viaje v JOIN v.ruta r "
            + "WHERE (v.fecha > :hoy OR (v.fecha = :hoy AND v.hora > :horaActual)) "
            + "AND v.estado = :estado "
            + "AND r.estado <> :rutaEliminada "
            + "AND v.pasajeros < r.capacidadMaxima "
            + "AND LOWER(r.origen) LIKE CONCAT('%', COALESCE(:origen, ''), '%') "
            + "AND LOWER(r.destino) LIKE CONCAT('%', COALESCE(:destino, ''), '%') "
            + "AND LOWER(v.dia) = COALESCE(:dia, LOWER(v.dia)) "
            + "AND v.hora = COALESCE(:hora, v.hora) "
            + "AND LOWER(r.origen) LIKE CONCAT('%', COALESCE(:distrito, ''), '%') "
            + "ORDER BY "
            + "(CASE WHEN COALESCE(:origen, '') = '' OR LOWER(r.origen) = :origen THEN 0 ELSE 4 END "
            + "+ CASE WHEN COALESCE(:destino, '') = '' OR LOWER(r.destino) = :destino THEN 0 ELSE 3 END "
            + "+ CASE WHEN COALESCE(:distrito, '') = '' OR LOWER(r.origen) = :distrito THEN 0 ELSE 1 END) ASC, "
            + "v.fecha ASC, v.hora ASC, v.id ASC")
    Page<Viaje> buscarViajesRelevantes(
            @Param("origen") String origen, @Param("destino") String destino,
            @Param("dia") String dia, @Param("hora") LocalTime hora,
            @Param("distrito") String distrito, @Param("estado") EstadoViaje estado,
            @Param("rutaEliminada") com.uniride.enums.EstadoRuta rutaEliminada,
            @Param("hoy") LocalDate hoy, @Param("horaActual") LocalTime horaActual,
            Pageable pageable);

    @Query("SELECT v FROM Viaje v WHERE v.ruta.conductor.id = :conductorId "
            + "AND (v.estado <> :programado OR v.fecha <= :hoy) "
            + "ORDER BY v.fecha DESC, v.hora DESC")
    List<Viaje> historialComoConductor(@Param("conductorId") Long conductorId,
            @Param("programado") EstadoViaje programado, @Param("hoy") LocalDate hoy);

    @Query("SELECT v FROM Viaje v JOIN v.ruta r "
            + "WHERE v.fecha >= CURRENT_DATE AND v.estado = :estado "
            + "AND LOWER(r.origen) = COALESCE(:origen, LOWER(r.origen)) "
            + "AND LOWER(r.destino) = COALESCE(:destino, LOWER(r.destino)) "
            + "AND LOWER(v.dia) = COALESCE(:dia, LOWER(v.dia)) "
            + "AND v.hora = COALESCE(:hora, v.hora) "
            + "AND LOWER(r.origen) LIKE COALESCE(:distrito, '%') "
            + "ORDER BY v.fecha ASC, v.hora ASC")
    Page<Viaje> buscarViajes(@Param("origen") String origen, @Param("destino") String destino,
            @Param("dia") String dia, @Param("hora") LocalTime hora,
            @Param("distrito") String distrito, @Param("estado") EstadoViaje estado,
            Pageable pageable);

    @Query("SELECT v FROM Viaje v WHERE v.ruta.conductor.id = :conductorId "
            + "AND v.confirmado = false AND v.estado = :estado "
            + "AND v.fecha BETWEEN :desde AND :hasta")
    List<Viaje> buscarPendientesDeConfirmacion(@Param("conductorId") Long conductorId,
            @Param("estado") EstadoViaje estado, @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    // Bloqueo de filas durante el ciclo de recordatorios para impedir dos envios
    // simultaneos en distintas ejecuciones del scheduler.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Viaje v "
            + "WHERE v.confirmado = false "
            + "AND v.recordatorioEnviado = false "
            + "AND v.estado = :estado "
            + "AND v.fecha BETWEEN :desde AND :hasta")
    List<Viaje> buscarPendientesDeConfirmacionAutomaticos(
            @Param("estado") EstadoViaje estado,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);
}
