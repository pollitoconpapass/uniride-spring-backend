package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.uniride.entities.Ruta;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContribucionBusquedaUs10Test {
    @Test
    void referenciaDeBusquedaUsaMismaTarifaQueElEndpointDetallado() {
        ViajeRepository repository=mock(ViajeRepository.class);
        Viaje viaje=Viaje.builder().id(90L)
                .ruta(Ruta.builder().origen("Surco").destino("UPC").build())
                .fecha(LocalDate.now().plusDays(2)).hora(LocalTime.of(8,0)).dia("Lunes")
                .pasajeros(2).estado(EstadoViaje.PROGRAMADO).build();
        when(repository.findById(90L)).thenReturn(Optional.of(viaje));
        var buscador=ContribucionService.referenciaBusqueda(viaje);
        var detalle=new ContribucionService(repository).sugerencia(90L,2);
        assertThat(buscador.aporteEstimado()).isEqualTo(detalle.aporteEstimado());
        assertThat(buscador.litrosGasolinaAprox()).isEqualTo(detalle.litrosGasolinaAprox());
        assertThat(buscador.sugerenciaFavor()).isNotBlank();
    }

    @Test
    void referenciaSinPasajerosUsaUnoDeFormaExplícita() {
        Viaje viaje=Viaje.builder().id(91L)
                .ruta(Ruta.builder().origen("Surco").destino("UPC").build())
                .fecha(LocalDate.now().plusDays(2)).hora(LocalTime.of(12,0)).dia("Lunes")
                .estado(EstadoViaje.PROGRAMADO).build();
        var referencia=ContribucionService.referenciaBusqueda(viaje);
        assertThat(referencia.pasajeros()).isEqualTo(1);
        assertThat(referencia.nota()).contains("1 pasajero");
    }
}
