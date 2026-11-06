package com.uniride.repositories;

import static org.assertj.core.api.Assertions.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class EstadisticasHardeningRepositoryTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PenalidadRepository penalidades;

    Usuario usuario() {
        return usuarios.save(Usuario.builder().nombre("Ana").apellidos("Perez").contrasenaHash("hash")
                .correoInstitucional(UUID.randomUUID()+"@upc.edu.pe").telefono(UUID.randomUUID().toString()).build());
    }
    Viaje viaje(Ruta ruta, EstadoViaje estado, Usuario autor) {
        return viajes.save(Viaje.builder().ruta(ruta).fecha(LocalDate.of(2020,1,6)).hora(LocalTime.NOON)
                .dia("Lunes").estado(estado).canceladoPor(autor).build());
    }
    @Test void autorSinPenalidadTercerosConPenalidadYDesconocidosNoSeConfunden() {
        Usuario conductor=usuario(), pasajero=usuario();
        Ruta ruta=rutas.save(Ruta.builder().conductor(conductor).origen("Surco").destino("UPC")
                .capacidadMaxima(2).puntosReferencia("Puente").build());
        viaje(ruta,EstadoViaje.CANCELADO,conductor); // Propia sin penalidad.
        Viaje tercero=viaje(ruta,EstadoViaje.CANCELADO,pasajero);
        viaje(ruta,EstadoViaje.CANCELADO,null); // Histórico: no inventar autor.
        Viaje vencido=viaje(ruta,EstadoViaje.VENCIDO,null);
        penalidades.save(Penalidad.builder().usuario(conductor).viaje(vencido).tipo(TipoPenalidad.LEVE).motivo("No confirmó").build());
        penalidades.save(Penalidad.builder().usuario(pasajero).viaje(tercero).tipo(TipoPenalidad.GRAVE).motivo("Canceló").build());
        assertThat(viajes.countByEstadoAndCanceladoPorId(EstadoViaje.CANCELADO,conductor.getId())).isEqualTo(1);
        assertThat(viajes.contarCanceladosPorTerceros(conductor.getId(),EstadoViaje.CANCELADO)).isEqualTo(1);
    }
    @Test void rankingSoloPenalizaAlConductorYFrecuenciaEsHistorica() {
        Usuario conductor=usuario(), pasajero=usuario();
        Ruta ruta=rutas.save(Ruta.builder().conductor(conductor).origen("Surco").destino("UPC")
                .capacidadMaxima(2).puntosReferencia("Puente").build());
        Viaje v=viaje(ruta,EstadoViaje.COMPLETADO,null);
        for(Usuario u:List.of(conductor,pasajero)) penalidades.save(Penalidad.builder().usuario(u).viaje(v)
                .tipo(TipoPenalidad.LEVE).motivo("Prueba").build());
        var ranking=viajes.penalidadesPorRutaComoConductor(conductor.getId());
        assertThat(((Number)ranking.getFirst()[2]).longValue()).isEqualTo(1);
        var dias=viajes.frecuenciaPorDiaComoConductor(conductor.getId(),EstadoViaje.COMPLETADO);
        assertThat(((Number)dias.getFirst()[1]).longValue()).isEqualTo(1); // Viaje de 2020.
    }
    @Test void solicitudRechazadaOCanceladaPreviamenteNoEsCancelacionDeTerceros() {
        Usuario conductor=usuario(), pasajero=usuario();
        Ruta ruta=rutas.save(Ruta.builder().conductor(conductor).origen("Surco").destino("UPC")
                .capacidadMaxima(2).puntosReferencia("Puente").build());
        for(EstadoSolicitud estado:List.of(EstadoSolicitud.RECHAZADA,EstadoSolicitud.CANCELADA)) {
            Viaje viaje=viaje(ruta,EstadoViaje.CANCELADO,conductor);
            solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero).estado(estado)
                    .preferenciaCompensacion(TipoCompensacion.DINERO).build());
        }
        assertThat(viajes.contarCanceladosPorTerceros(pasajero.getId(),EstadoViaje.CANCELADO)).isZero();
    }
}
