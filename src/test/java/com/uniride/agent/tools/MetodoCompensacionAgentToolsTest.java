package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetodoCompensacionAgentToolsTest {
    private final AgentQueryService consultas = mock(AgentQueryService.class);
    private final ConfirmationService confirmaciones = mock(ConfirmationService.class);
    private final MetodoCompensacionAgentTools tools = new MetodoCompensacionAgentTools(consultas, confirmaciones);

    @Test void prepararNoRegistraMetodo() {
        var request = new MetodoCompensacionRequest(TipoCompensacion.GASOLINA, "Compartir combustible", true);
        var result = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el método.");
        when(confirmaciones.prepararMetodo(request)).thenReturn(result);
        assertThat(tools.registrarMetodoCompensacion(request.tipo(), request.descripcion())).isSameAs(result);
        verify(confirmaciones).prepararMetodo(request);
        verifyNoInteractions(consultas);
    }

    @Test void datosFaltantesNoPreparanNada() {
        assertThat(tools.registrarMetodoCompensacion(null, "").estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test void consultarMetodosEsPaginado() {
        var page = new PaginaAgente<>(List.of(new MetodoCompensacionResumen(11L, TipoCompensacion.FAVOR, "Apuntes", true)),
                1, 10, 25, true);
        when(consultas.metodos(1)).thenReturn(page);
        assertThat(tools.consultarMisMetodosCompensacion(1)).isSameAs(page);
        assertThat(tools.consultarMisMetodosCompensacion(1).hayMas()).isTrue();
        verifyNoInteractions(confirmaciones);
    }

    @Test void preparaCambioSoloDeDescripcion() {
        var pendiente = pendiente("Cambiar la descripción de Yape a Yape personal.");
        when(confirmaciones.prepararActualizacionMetodo(null, "Yape", null, "Yape personal"))
                .thenReturn(pendiente);

        assertThat(tools.actualizarMetodoCompensacion(null, "Yape", null, "Yape personal"))
                .isSameAs(pendiente);
        verify(confirmaciones).prepararActualizacionMetodo(null, "Yape", null, "Yape personal");
        verifyNoMoreInteractions(confirmaciones);
        verifyNoInteractions(consultas);
    }

    @Test void preparaCambioSoloDeTipo() {
        var pendiente = pendiente("Cambiar el tipo de Favor con apuntes a DINERO.");
        when(confirmaciones.prepararActualizacionMetodo(
                TipoCompensacion.FAVOR, "Favor con apuntes", TipoCompensacion.DINERO, null)).thenReturn(pendiente);

        assertThat(tools.actualizarMetodoCompensacion(
                TipoCompensacion.FAVOR, "Favor con apuntes", TipoCompensacion.DINERO, null)).isSameAs(pendiente);
        verify(confirmaciones).prepararActualizacionMetodo(
                TipoCompensacion.FAVOR, "Favor con apuntes", TipoCompensacion.DINERO, null);
        verifyNoMoreInteractions(confirmaciones);
        verifyNoInteractions(consultas);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preparaElEstadoSolicitado(boolean activo) {
        var pendiente = pendiente("Cambiar el estado del método de gasolina.");
        when(confirmaciones.prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, activo)).thenReturn(pendiente);

        assertThat(tools.cambiarEstadoMetodoCompensacion(TipoCompensacion.GASOLINA, null, activo))
                .isSameAs(pendiente);
        verify(confirmaciones).prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, activo);
        verifyNoMoreInteractions(confirmaciones);
        verifyNoInteractions(consultas);
    }

    @Test void preparaEliminarSoloElMetodoIdentificado() {
        var pendiente = pendiente("Eliminar el método FAVOR Favor con apuntes.");
        when(confirmaciones.prepararEliminacionMetodo(TipoCompensacion.FAVOR, "Favor con apuntes"))
                .thenReturn(pendiente);

        assertThat(tools.eliminarMetodoCompensacion(TipoCompensacion.FAVOR, "Favor con apuntes"))
                .isSameAs(pendiente);
        verify(confirmaciones).prepararEliminacionMetodo(TipoCompensacion.FAVOR, "Favor con apuntes");
        verifyNoMoreInteractions(confirmaciones);
        verifyNoInteractions(consultas);
    }

    @Test void finDelFlujoSinConfirmacionParaMetodosAmbiguosOYaEnElEstadoDeseado() {
        var ambiguo = new OperacionResumen(EstadoOperacion.AMBIGUO, null, "Hay varios métodos de gasolina.");
        var igual = new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "El método ya está activo.");
        when(confirmaciones.prepararEliminacionMetodo(TipoCompensacion.GASOLINA, null)).thenReturn(ambiguo);
        when(confirmaciones.prepararEstadoMetodo(null, "Yape", true)).thenReturn(igual);

        assertThat(tools.eliminarMetodoCompensacion(TipoCompensacion.GASOLINA, null)).isSameAs(ambiguo);
        assertThat(tools.cambiarEstadoMetodoCompensacion(null, "Yape", true)).isSameAs(igual);
        assertThat(ambiguo.confirmacion()).isNull();
        assertThat(igual.confirmacion()).isNull();
    }

    @Test void propiedadAusenteSeMuestraComoMetodoNoEncontrado() {
        when(confirmaciones.prepararEliminacionMetodo(null, "Método reservado"))
                .thenThrow(new ResourceNotFoundException("Método de compensación no encontrado"));

        var resultado = tools.eliminarMetodoCompensacion(null, "Método reservado");

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.NO_ENCONTRADO);
        assertThat(resultado.detalle()).contains("No encontré", "método de compensación propio");
        assertThat(resultado.confirmacion()).isNull();
    }

    @Test void rechazaLaEliminacionVinculadaSinPrepararUnCambioAlternativo() {
        when(confirmaciones.prepararEliminacionMetodo(null, "Yape")).thenThrow(new BusinessException(
                "No puedes eliminar este método porque tiene solicitudes asociadas. Puedes desactivarlo."));

        var resultado = tools.eliminarMetodoCompensacion(null, "Yape");

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.ERROR);
        assertThat(resultado.detalle()).contains("solicitudes asociadas", "desactivarlo");
        assertThat(resultado.confirmacion()).isNull();
        verify(confirmaciones).prepararEliminacionMetodo(null, "Yape");
        verifyNoMoreInteractions(confirmaciones);
    }

    @Test void faltanIdentificadorCambiosOEstadoExplicito() {
        var faltante = new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica el dato que falta.");
        when(confirmaciones.prepararEliminacionMetodo(null, " ")).thenReturn(faltante);
        when(confirmaciones.prepararActualizacionMetodo(null, "Yape", null, null)).thenReturn(faltante);
        when(confirmaciones.prepararEstadoMetodo(null, "Yape", null)).thenReturn(faltante);

        assertThat(tools.eliminarMetodoCompensacion(null, " ")).isSameAs(faltante);
        assertThat(tools.actualizarMetodoCompensacion(null, "Yape", null, null)).isSameAs(faltante);
        assertThat(tools.cambiarEstadoMetodoCompensacion(null, "Yape", null)).isSameAs(faltante);
        assertThat(faltante.confirmacion()).isNull();
        verifyNoInteractions(consultas);
    }

    private static OperacionResumen pendiente(String detalle) {
        return new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, detalle);
    }
}
