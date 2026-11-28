package com.uniride.agent.tools;

import com.uniride.agent.tools.pasajero.ContribucionTools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.uniride.agent.support.AgentQueryService;
import com.uniride.agent.support.AgentQueryService.ViajeContribucion;
import com.uniride.agent.dto.ContribucionFiltros;
import com.uniride.agent.dto.ContribucionResultado.Estado;
import com.uniride.agent.dto.ContribucionResultado.Opcion;
import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.services.ContribucionService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ContribucionAgentToolsTest {
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 25);
    private static final LocalTime HORA = LocalTime.of(8, 0);
    private static final Opcion OPCION = new Opcion("Surco", "UPC", "Miercoles", FECHA, HORA, "Ana");
    private static final ViajeContribucion VIAJE = new ViajeContribucion(27L, OPCION);

    @Mock AgentQueryService consultas;
    @Mock ContribucionService contribuciones;
    private ContribucionTools herramienta;

    @BeforeEach
    void configurar() {
        herramienta = new ContribucionTools(consultas, contribuciones);
    }

    @Test
    void pasaElIdAutorizadoAlServicioYPresentaSusCifrasYLosPasajerosAceptados() {
        var filtros = filtros(FECHA, HORA, 9);
        var sugerencia = new SugerenciaContribucionRespuesta(27L, "Surco", "UPC", "Miercoles", FECHA, HORA,
                3, true, 12.0, 4.0, 0.74, "Un favor acordado", "La estimación está sujeta a acuerdo.");
        when(consultas.identificarViajes(filtros)).thenReturn(new PageImpl<>(List.of(VIAJE)));
        when(contribuciones.sugerencia(27L, 9)).thenReturn(sugerencia);

        var resultado = herramienta.sugerirContribucionViaje(contexto(filtros));

        assertThat(resultado.estado()).isEqualTo(Estado.SUGERIDA);
        assertThat(resultado.sugerencia()).isSameAs(sugerencia);
        assertThat(resultado.mensaje()).contains("Surco a UPC", "2026-11-25", "08:00", "hora pico",
                "S/ 12.00", "3 pasajeros", "S/ 4.00", "0.74 litros", "Un favor acordado", "sujeta a acuerdo",
                "cantidad de pasajeros ya aceptados");
        verify(contribuciones).sugerencia(27L, 9);
    }

    @Test
    void pideCantidadCuandoElServicioLaRequiereYExponeElViajeParaContinuar() {
        var filtros = filtros(FECHA, HORA, null);
        when(consultas.identificarViajes(filtros)).thenReturn(new PageImpl<>(List.of(VIAJE)));
        when(contribuciones.sugerencia(27L, null)).thenThrow(new CamposInvalidosException("Indica la cantidad de pasajeros"));

        var resultado = herramienta.sugerirContribucionViaje(contexto(filtros));

        assertThat(resultado.estado()).isEqualTo(Estado.FALTAN_PASAJEROS);
        assertThat(resultado.mensaje()).contains("cantidad de pasajeros");
        assertThat(resultado.sugerencia()).isNull();
        assertThat(resultado.alternativas()).containsExactly(OPCION);
    }

    @Test
    void muestraLasCoincidenciasYPreguntaPorLaFechaSiDifiere() {
        var filtros = filtros(null, HORA, 2);
        var opcion = new Opcion("Surco", "UPC", "Jueves", FECHA.plusDays(1), HORA, "Ana");
        when(consultas.identificarViajes(filtros)).thenReturn(new PageImpl<>(
                List.of(VIAJE, new ViajeContribucion(42L, opcion)), PageRequest.of(0, 2), 3));

        var resultado = herramienta.sugerirContribucionViaje(contexto(filtros));

        assertThat(resultado.estado()).isEqualTo(Estado.AMBIGUA);
        assertThat(resultado.alternativas()).containsExactly(OPCION, opcion);
        assertThat(resultado.hayMasAlternativas()).isTrue();
        assertThat(resultado.sugerencia()).isNull();
        assertThat(resultado.mensaje()).contains("2026-11-25", "2026-11-26", "¿Qué fecha");
        verifyNoInteractions(contribuciones);
    }

    @Test
    void preguntaPorLaHoraSiVariasSalidasCompartenFecha() {
        var filtros = filtros(FECHA, null, 2);
        var opcion = new Opcion("Surco", "UPC", "Miercoles", FECHA, HORA.plusHours(1), "Ana");
        when(consultas.identificarViajes(filtros)).thenReturn(new PageImpl<>(
                List.of(VIAJE, new ViajeContribucion(42L, opcion))));

        var resultado = herramienta.sugerirContribucionViaje(contexto(filtros));

        assertThat(resultado.estado()).isEqualTo(Estado.AMBIGUA);
        assertThat(resultado.mensaje()).contains("08:00", "09:00", "¿A qué hora");
        verifyNoInteractions(contribuciones);
    }

    @Test
    void noSeleccionaUnaSalidaAunqueCompartanTodosSusDatosVisibles() {
        var filtros = filtros(FECHA, HORA, 2);
        when(consultas.identificarViajes(filtros)).thenReturn(new PageImpl<>(
                List.of(VIAJE, new ViajeContribucion(42L, OPCION))));

        var resultado = herramienta.sugerirContribucionViaje(contexto(filtros));

        assertThat(resultado.estado()).isEqualTo(Estado.AMBIGUA);
        assertThat(resultado.mensaje()).contains("mismos datos visibles");
        verifyNoInteractions(contribuciones);
    }

    @Test
    void diferenciaAusenciaDeFiltrosDeAusenciaDeCoincidencias() {
        var conFiltros = filtros(FECHA, HORA, 2);
        var soloCantidad = new ContribucionFiltros(null, null, null, null, null, 2, null, false);
        when(consultas.identificarViajes(conFiltros)).thenReturn(Page.empty());
        when(consultas.identificarViajes(soloCantidad)).thenReturn(Page.empty());

        assertThat(herramienta.sugerirContribucionViaje(contexto(conFiltros)).estado()).isEqualTo(Estado.NO_ENCONTRADO);
        assertThat(herramienta.sugerirContribucionViaje(contexto(soloCantidad)).estado()).isEqualTo(Estado.FALTAN_FILTROS);
        verifyNoInteractions(contribuciones);
    }

    @Test
    void consultaSinPermisoOSinSesionNoLlegaAlCalculo() {
        var filtros = filtros(FECHA, HORA, 2);
        when(consultas.identificarViajes(filtros)).thenThrow(new NoPermitidoException("Requiere PASAJERO"))
                .thenThrow(new NoAutorizadoException("Debes iniciar sesión"));

        assertThatThrownBy(() -> herramienta.sugerirContribucionViaje(contexto(filtros)))
                .isInstanceOf(NoPermitidoException.class);
        assertThatThrownBy(() -> herramienta.sugerirContribucionViaje(contexto(filtros)))
                .isInstanceOf(NoAutorizadoException.class);
        verifyNoInteractions(contribuciones);
    }

    private static ContribucionFiltros filtros(LocalDate fecha, LocalTime hora, Integer pasajeros) {
        return new ContribucionFiltros("Surco", "UPC", null, fecha, hora, pasajeros, null, false);
    }

    private static ToolContext contexto(ContribucionFiltros filtros) {
        return new ToolContext(Map.of(ContribucionTools.CONTEXTO_FILTROS, filtros));
    }
}
