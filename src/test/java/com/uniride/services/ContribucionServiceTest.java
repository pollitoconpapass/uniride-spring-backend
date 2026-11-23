package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uniride.entities.Ruta;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContribucionServiceTest {
    private static final long VIAJE_ID = 27L;
    private final ViajeRepository viajes = mock(ViajeRepository.class);
    private final ContribucionService contribuciones = new ContribucionService(viajes);

    @ParameterizedTest
    @CsvSource({
        "05:59:59, false, 8.0", "06:00, true, 12.0", "08:00, true, 12.0", "09:59:59, true, 12.0",
        "10:00, false, 8.0", "12:00, false, 8.0", "16:59:59, false, 8.0", "17:00, true, 12.0",
        "18:00, true, 12.0", "20:59:59, true, 12.0", "21:00, false, 8.0"
    })
    void conservaLosIntervalosDeHoraPicoSemiabiertos(String hora, boolean pico, double total) {
        guardar(LocalTime.parse(hora), 0, EstadoViaje.PROGRAMADO);

        var sugerencia = contribuciones.sugerencia(VIAJE_ID, 1);

        assertThat(sugerencia.pasajeros()).isEqualTo(1);
        assertThat(sugerencia.esHoraPico()).isEqualTo(pico);
        assertThat(sugerencia.costoTotalEstimado()).isEqualTo(total);
        assertThat(sugerencia.aporteEstimado()).isEqualTo(total);
    }

    @Test
    void exigeLaCantidadPositivaCuandoNoHayAceptadosYDivideEntreElGrupoIndicado() {
        guardar(LocalTime.NOON, 0, EstadoViaje.PROGRAMADO);

        assertThatThrownBy(() -> contribuciones.sugerencia(VIAJE_ID, null))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("indica la cantidad");
        assertThatThrownBy(() -> contribuciones.sugerencia(VIAJE_ID, 0))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("al menos 1");

        var sugerencia = contribuciones.sugerencia(VIAJE_ID, 3);
        assertThat(sugerencia.pasajeros()).isEqualTo(3);
        assertThat(sugerencia.aporteEstimado()).isEqualTo(2.67);
        assertThat(sugerencia.litrosGasolinaAprox()).isEqualTo(0.49);
        assertThat(sugerencia.sugerenciaFavor()).contains("favor acordado");
        assertThat(sugerencia.nota()).contains("acuerdo entre conductor y pasajeros");
    }

    @Test
    void siempreUsaElConteoDeAceptadosAunqueElSolicitadoSeaDistinto() {
        guardar(LocalTime.of(18, 0), 2, EstadoViaje.PROGRAMADO);

        var sugerencia = contribuciones.sugerencia(VIAJE_ID, 12);

        assertThat(sugerencia.pasajeros()).isEqualTo(2);
        assertThat(sugerencia.aporteEstimado()).isEqualTo(6.0);
        assertThat(sugerencia.litrosGasolinaAprox()).isEqualTo(1.11);
        assertThat(contribuciones.sugerencia(VIAJE_ID, null)).isEqualTo(sugerencia);
    }

    @Test
    void rechazaViajeQueNoEstaProgramado() {
        guardar(LocalTime.NOON, 0, EstadoViaje.CANCELADO);

        assertThatThrownBy(() -> contribuciones.sugerencia(VIAJE_ID, 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("CANCELADO");
    }

    private void guardar(LocalTime hora, int aceptados, EstadoViaje estado) {
        var ruta = Ruta.builder().origen("Surco").destino("UPC").build();
        var viaje = Viaje.builder().id(VIAJE_ID).ruta(ruta).dia("Miercoles")
                .fecha(LocalDate.of(2026, 11, 25)).hora(hora).estado(estado).pasajeros(aceptados).build();
        when(viajes.findById(VIAJE_ID)).thenReturn(Optional.of(viaje));
    }
}
