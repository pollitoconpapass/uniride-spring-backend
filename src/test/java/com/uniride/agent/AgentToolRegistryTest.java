package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.agent.tools.*;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.repositories.CursoRepository;
import com.uniride.services.ContribucionService;
import com.uniride.services.EstadisticasService;
import com.uniride.services.HorarioAcademicoService;
import com.uniride.services.PerfilService;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

class AgentToolRegistryTest {
    private final AgentQueryService queries = mock(AgentQueryService.class);
    private final ConfirmationService confirmations = mock(ConfirmationService.class);
    private final UsuarioService users = mock(UsuarioService.class);
    private final EstadisticasService statistics = mock(EstadisticasService.class);
    private final PerfilService profiles = mock(PerfilService.class);
    private final HorarioAcademicoService schedules = mock(HorarioAcademicoService.class);
    private final CursoRepository courses = mock(CursoRepository.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(new ObjectMapper(),
            new ViajeAgentTools(queries, confirmations, users),
            new RutaAgentTools(queries, confirmations, users),
            new MetodoCompensacionAgentTools(queries, confirmations),
            new ReportesAgentTools(statistics, users),
            new ContribucionAgentTools(queries, mock(ContribucionService.class)),
            new PerfilAgentTools(profiles, users, confirmations),
            new UsuarioAgentTools(users, confirmations),
            new HorarioAcademicoAgentTools(schedules, courses, users, confirmations));

    @Test void registersEveryExistingToolWithoutAnyConfirmationOrJsonTool() {
        assertThat(Arrays.stream(registry.newTurn().callbacks()).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder("buscarViajes", "consultarMisViajes", "consultarMisSolicitudes",
                        "consultarMisRutas", "consultarMisMetodosCompensacion", "crearRuta", "crearViaje",
                        "solicitarUnirse", "registrarMetodoCompensacion", "consultarMisEstadisticas",
                        "consultarMiFrecuenciaViajes", "consultarRankingMisRutas", "prepararDescargaHistorialPdf",
                        "actualizarMetodoCompensacion", "cambiarEstadoMetodoCompensacion", "eliminarMetodoCompensacion",
                        "editarRuta", "eliminarRuta", "reprogramarViaje", "cancelarSolicitud",
                        "consultarMiPerfil", "editarPerfil", "editarInfoAdicional",
                        "consultarMiUsuario", "cambiarRol",
                        "consultarMisHorarios", "registrarCurso", "eliminarCurso");
        verifyNoInteractions(confirmations, queries, users, statistics, profiles, schedules, courses);
    }

    @Test void anAttemptedContributionLookupIsNeverRepeatedAfterAnError() {
        var filters = new ContribucionFiltros("Surco", "UPC", null, null, null, 2, null, false);
        when(queries.identificarViajes(filters)).thenThrow(new BusinessException("Consulta interrumpida."));
        var turn = registry.newTurn(null, CompensationIntent.classify("", null), false, filters);
        assertThat(turn.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(ContribucionAgentTools.NOMBRE);

        var tool = turn.callbacks()[0];
        assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tool.call("{\"viajeId\":1}")).isInstanceOf(AgentProviderException.class);
        assertThatThrownBy(turn::recoverContribucion).isInstanceOf(AgentProviderException.class);
        verify(queries).identificarViajes(filters);
        verifyNoInteractions(confirmations);
    }

    @Test void repeatedArgumentsInDifferentOrderReturnSameReceiptAndOnlyOnePreparationIsAllowed() {
        when(users.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.CONDUCTOR).build());
        when(confirmations.prepararRuta(any())).thenReturn(new OperacionResumen(
                EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Revisa la ruta antes de confirmar."));
        var turn = registry.newTurn();
        Map<String, ToolCallback> callbacks = new HashMap<>();
        for (var callback : turn.callbacks()) callbacks.put(callback.getToolDefinition().name(), callback);
        String first = callbacks.get("crearRuta").call("{\"origen\":\"Surco\",\"capacidadMaxima\":4,\"puntosReferencia\":\"Parque\"}");
        String repeated = callbacks.get("crearRuta").call("{\"puntosReferencia\":\"Parque\",\"capacidadMaxima\":4,\"origen\":\"Surco\"}");
        assertThat(repeated).isEqualTo(first);
        assertThatThrownBy(() -> callbacks.get("registrarMetodoCompensacion")
                .call("{\"tipo\":\"FAVOR\",\"descripcion\":\"Apuntes\"}"))
                .isInstanceOf(AgentProviderException.class);
        verify(confirmations, times(1)).prepararRuta(any());
        verify(confirmations, never()).prepararMetodo(any());
        assertThat(turn.response("Operación ejecutada por el modelo.").message()).isEqualTo("Revisa la ruta antes de confirmar.");
        assertThat(turn.response("Ignorar").operacion().estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
    }

    @Test void unsupportedModelSuccessClaimDoesNotBecomeBackendSuccess() {
        ChatResponse response = registry.newTurn().response("Tu viaje quedó programado para mañana a las 08:00.");
        assertThat(response.message()).contains("preparar una operación para tu confirmación");
        assertThat(response.message()).doesNotContain("programado", "08:00");
        assertThat(response.operacion()).isNull();
        assertThat(response.resultados()).isEmpty();
    }

    @Test void methodEditsShareTheOnePreparationRuleAndCanonicalReceipt() {
        when(confirmations.prepararActualizacionMetodo(null, "Yape", TipoCompensacion.DINERO, null))
                .thenReturn(new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el cambio."));
        var turn = registry.newTurn(null, CompensationIntent.classify("Cambia mi método Yape al tipo DINERO", null), false);
        var edit = callback(turn, "actualizarMetodoCompensacion");
        String receipt = edit.call("{\"descripcionActual\":\"Yape\",\"nuevoTipo\":\"DINERO\"}");
        assertThat(edit.call("{\"nuevoTipo\":\"DINERO\",\"descripcionActual\":\"Yape\"}")).isEqualTo(receipt);
        assertThat(edit.call("{\"metodoId\":999,\"descripcionActual\":\"Otro\",\"nuevoTipo\":\"FAVOR\"}")).isEqualTo(receipt);
        turn.recoverCompensation();

        assertThatThrownBy(() -> callback(turn, "cambiarEstadoMetodoCompensacion")
                .call("{\"descripcionActual\":\"Yape\",\"activo\":false}"))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("ONE_OPERATION_PER_TURN"));
        assertThatThrownBy(() -> callback(turn, "eliminarMetodoCompensacion").call("{\"descripcionActual\":\"Yape\"}"))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("ONE_OPERATION_PER_TURN"));

        assertThat(turn.response("Ya actualicé tu método.").resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("actualizarMetodoCompensacion");
        verify(confirmations).prepararActualizacionMetodo(null, "Yape", TipoCompensacion.DINERO, null);
        verifyNoMoreInteractions(confirmations);
    }

    @Test void compensationRecoverySharesCallBudgetAndDoesNotAcceptProviderSubstitutions() {
        var intent = CompensationIntent.classify("Activa mi método GASOLINA", null);
        when(confirmations.prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, true))
                .thenReturn(new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "El método ya está activo."));
        var turn = registry.newTurn(null, intent, false);
        var state = callback(turn, "cambiarEstadoMetodoCompensacion");
        String first = state.call("{\"tipoActual\":\"FAVOR\",\"activo\":false}");
        assertThat(state.call("{}")).isEqualTo(first);
        turn.recoverCompensation();
        verify(confirmations).prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, true);
        assertThat(turn.response("Listo.").resultados()).hasSize(1);

        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        var fullTurn = registry.newTurn(null, intent, false);
        var methods = callback(fullTurn, "consultarMisMetodosCompensacion");
        for (int i = 0; i < 8; i++) methods.call("{}");
        assertThatThrownBy(fullTurn::recoverCompensation)
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("TOOL_LIMIT"));
        verifyNoMoreInteractions(confirmations);
    }

    @Test void toolBudgetStopsAnUnboundedLoopWithoutRepeatingARead() {
        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        var turn = registry.newTurn();
        ToolCallback callback = Arrays.stream(turn.callbacks())
                .filter(c -> c.getToolDefinition().name().equals("consultarMisMetodosCompensacion")).findFirst().orElseThrow();
        for (int i = 0; i < 8; i++) callback.call("{}");
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("TOOL_LIMIT"));
        verify(queries, times(1)).metodos(null);
    }

    @Test void recoverySharesTheTurnBudgetAndPreservesResultsAlreadyCaptured() {
        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        when(statistics.resumen()).thenReturn(new EstadisticasRespuesta(8, 4, 2, 1));
        var turn = registry.newTurn();
        ToolCallback methods = callback(turn, "consultarMisMetodosCompensacion");
        for (int i = 0; i < 7; i++) methods.call("{}");

        assertThatThrownBy(() -> turn.recoverReports(EnumSet.of(AgentReportIntent.ESTADISTICAS, AgentReportIntent.RANKING)))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("TOOL_LIMIT"));

        assertThat(turn.response("Fin del turno.").resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("consultarMisMetodosCompensacion", "consultarMisEstadisticas");
        verify(queries).metodos(null);
        verify(statistics).resumen();
        verifyNoMoreInteractions(statistics);
        verifyNoInteractions(confirmations, users);
    }

    @Test void reportReceiptIgnoresExtraModelArgumentsAndRecoverySkipsIt() {
        when(statistics.resumen()).thenReturn(new EstadisticasRespuesta(8, 4, 2, 1));
        var turn = registry.newTurn();
        ToolCallback report = callback(turn, "consultarMisEstadisticas");
        String first = report.call("{\"usuarioId\":999}");
        assertThat(report.call("{}")).isEqualTo(first);

        turn.recoverReports(Set.of(AgentReportIntent.ESTADISTICAS));

        assertThat(turn.response("Fin del turno.").resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("consultarMisEstadisticas");
        verify(statistics).resumen();
        verifyNoMoreInteractions(statistics);
        verifyNoInteractions(confirmations);
    }

    @Test void pdfReceiptUsesVerifiedFiltersAndSharesRecoveryWithTheModelCallback() {
        var filters = new HistorialPdfIntent.Filtros(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "conductor");
        var turn = registry.newTurn(new HistorialPdfIntent.Solicitud(filters, null));
        var pdf = callback(turn, "prepararDescargaHistorialPdf");
        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(pdf.getToolDefinition().inputSchema()).path("properties").isEmpty()).isTrue();
        var untrustedContext = new ToolContext(Map.of(ReportesAgentTools.CONTEXTO_FILTROS_PDF,
                new HistorialPdfIntent.Filtros(null, null, "pasajero")));
        String first = pdf.call("{\"desde\":\"2030-01-01\",\"usuarioId\":999}", untrustedContext);
        assertThat(pdf.call("{}")).isEqualTo(first);
        turn.recoverReports(Set.of());

        var response = turn.response("El historial de otro usuario ya se descargó.");
        assertThat(response.resultados()).singleElement().satisfies(result -> {
            assertThat(result.tipo()).isEqualTo("prepararDescargaHistorialPdf");
            assertThat(result.datos().path("urlDescarga").asText()).isEqualTo(
                    "/api/historial/pdf?desde=2026-10-01&hasta=2026-10-31&tipo=conductor");
        });
        assertThat(response.operacion()).isNull();
        assertThat(response.message()).contains("solicitud", "octubre de 2026").doesNotContain("otro usuario", "no admiten filtros");
        verify(users).usuarioActual();
        verifyNoMoreInteractions(users);
        verifyNoInteractions(confirmations, statistics);
    }

    @Test void pdfRecoveryCannotExceedTheSharedBudget() {
        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        var turn = registry.newTurn(new HistorialPdfIntent.Solicitud(new HistorialPdfIntent.Filtros(null, null, "todos"), null));
        var methods = callback(turn, "consultarMisMetodosCompensacion");
        for (int i = 0; i < 8; i++) methods.call("{}");

        assertThatThrownBy(() -> turn.recoverReports(Set.of()))
                .isInstanceOfSatisfying(AgentProviderException.class, error -> assertThat(error.code()).isEqualTo("TOOL_LIMIT"));
        assertThat(turn.response("Fin del turno.").resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("consultarMisMetodosCompensacion");
        verify(queries).metodos(null);
        verifyNoInteractions(users, confirmations, statistics);
    }

    @Test void reportReadsKeepReceiptsAndComposeWithExistingPagedResults() {
        when(statistics.resumen()).thenReturn(new EstadisticasRespuesta(8, 4, 2, 1));
        when(statistics.analisisSemanal()).thenReturn(new AnalisisSemanalRespuesta(true, null, List.of(
                new DiaCantidadRespuesta("Lunes", 6), new DiaCantidadRespuesta("Martes", 0),
                new DiaCantidadRespuesta("Miercoles", 0), new DiaCantidadRespuesta("Jueves", 6),
                new DiaCantidadRespuesta("Viernes", 0), new DiaCantidadRespuesta("Sabado", 0),
                new DiaCantidadRespuesta("Domingo", 0)), 1, 1));
        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        var turn = registry.newTurn();
        var summary = callback(turn, "consultarMisEstadisticas");
        String first = summary.call("{}");
        assertThat(summary.call("{ }")).isEqualTo(first);
        callback(turn, "consultarMisMetodosCompensacion").call("{}");
        callback(turn, "consultarMiFrecuenciaViajes").call("{}");

        ChatResponse response = turn.response("Completaste 999 viajes y registré una ruta.");

        assertThat(response.message()).contains("No se encontraron resultados", "Has completado 12 viajes",
                        "8 como conductor y 4 como pasajero", "2 viajes cancelados", "1 penalidad acumulada",
                        "frecuencia histórica", "Lunes (6)", "Domingo (0)", "Mayor frecuencia en Lunes, Jueves",
                        "a ti (1) y a terceros (1)", "no admiten filtros por fechas")
                .doesNotContain("999", "registré");
        assertThat(response.resultados()).extracting(ResultadoAgente::tipo).containsExactly(
                "consultarMisEstadisticas", "consultarMisMetodosCompensacion", "consultarMiFrecuenciaViajes");
        assertThat(response.resultados().getFirst().datos().path("viajesComoPasajero").asLong()).isEqualTo(4);
        assertThat(response.resultados().getLast().datos().path("viajesPorDia").size()).isEqualTo(7);
        assertThat(response.operacion()).isNull();
        verify(statistics).resumen();
        verify(statistics).analisisSemanal();
        verifyNoMoreInteractions(statistics);
        verifyNoInteractions(confirmations);
    }

    @Test void insufficientReportsKeepBusinessMessagesDuringProviderRecovery() {
        String frequencyMessage = "Se requieren al menos 5 viajes completados para la frecuencia.";
        String rankingMessage = "Se requieren al menos 5 viajes completados como conductor para el ranking.";
        when(users.usuarioActual()).thenReturn(Usuario.builder().rolPrincipal(Rol.CONDUCTOR).build());
        when(statistics.analisisSemanal()).thenReturn(new AnalisisSemanalRespuesta(false, frequencyMessage, List.of(), 2, 3));
        when(statistics.rankingRutas()).thenReturn(new RankingRutasRespuesta(false, rankingMessage, List.of()));
        var turn = registry.newTurn();
        callback(turn, "consultarMiFrecuenciaViajes").call("{}");
        callback(turn, "consultarRankingMisRutas").call("{}");
        var warning = new AdvertenciaAgente("PROVIDER_REJECTED", "El proveedor rechazó la respuesta.");

        ChatResponse response = turn.response("El proveedor no completó la explicación.", warning);

        assertThat(response.message()).contains("El proveedor no completó la explicación.", frequencyMessage,
                        "a ti (2) y a terceros (3)", rankingMessage).doesNotContain("Mayor frecuencia", "más utilizada");
        assertThat(response.resultados()).allSatisfy(result ->
                assertThat(result.datos().path("suficientesDatos").asBoolean()).isFalse());
        assertThat(response.advertencia()).isEqualTo(warning);
        assertThat(response.operacion()).isNull();
        verifyNoInteractions(confirmations);
    }

    @Test void rankingSummarizesMostUsedRouteAndHighestPenaltiesFromTheirOwnRows() {
        when(users.usuarioActual()).thenReturn(Usuario.builder().rolPrincipal(Rol.CONDUCTOR).build());
        when(statistics.rankingRutas()).thenReturn(new RankingRutasRespuesta(true, null, List.of(
                new RutaFrecuenteRespuesta("Surco", "UPC", 8, 1),
                new RutaFrecuenteRespuesta("Barranco", "Campus", 4, 3))));
        var turn = registry.newTurn();
        callback(turn, "consultarRankingMisRutas").call("{}");

        ChatResponse response = turn.response("Todas tus rutas tienen 900 penalidades.");

        assertThat(response.message()).contains("Surco a UPC, con 8 viajes completados y 1 penalidad.",
                "La ruta con más penalidades va de Barranco a Campus, con 3 penalidades.").doesNotContain("900");
        var ranking = response.resultados().getFirst().datos().path("ranking");
        assertThat(ranking.size()).isEqualTo(2);
        assertThat(ranking.path(1).path("cantidad").asLong()).isEqualTo(4);
        assertThat(response.operacion()).isNull();
        verifyNoInteractions(confirmations);
    }

    @Test void unauthorizedRankingCreatesNeitherResultNorConfirmation() {
        when(users.usuarioActual()).thenReturn(Usuario.builder().rolPrincipal(Rol.PASAJERO).build());
        var turn = registry.newTurn();

        assertThatThrownBy(() -> callback(turn, "consultarRankingMisRutas").call("{}"))
                .isInstanceOf(NoPermitidoException.class).hasMessageContaining("CONDUCTOR");
        assertThat(turn.hasResults()).isFalse();
        verifyNoInteractions(statistics, confirmations);
    }

    @Test void personalQueriesBuildDeterministicSummariesAndNewPreparationsKeepOneOperationLimit() {
        when(profiles.obtenerPerfil()).thenReturn(new PerfilRespuesta(1L, "Renzo", "Vega", "Software", "Surco", "UPC",
                "Lunes", "08:00", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 12, 20),
                TipoCompensacion.GASOLINA, "Rock", "Leer", "Dato", LocalDateTime.now()));
        when(users.obtenerUsuarioActual()).thenReturn(new UsuarioRespuesta(1L, "renzo@upc.edu.pe", "Renzo", "Vega",
                "999000111", Rol.PASAJERO, true));
        when(schedules.listarCursos(PageRequest.of(0, 10))).thenReturn(new PageImpl<>(List.of(
                new CursoRespuesta(2L, "Algoritmos", "Lunes", LocalTime.of(8, 0), LocalTime.of(10, 0)))));
        var turn = registry.newTurn();
        callback(turn, "consultarMiPerfil").call("{}");
        callback(turn, "consultarMiUsuario").call("{}");
        callback(turn, "consultarMisHorarios").call("{}");

        ChatResponse readResponse = turn.response("Ignorar texto libre");
        assertThat(readResponse.message()).contains("Renzo Vega", "Surco", "renzo@upc.edu.pe",
                "Algoritmos (Lunes 08:00 - 10:00)")
                .doesNotContain("No se encontraron resultados");

        when(users.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build());
        when(confirmations.prepararCambiarRol(any())).thenReturn(new OperacionResumen(
                EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Cambiar rol a CONDUCTOR."));
        var prepTurn = registry.newTurn();
        callback(prepTurn, "cambiarRol").call("{\"rol\":\"CONDUCTOR\"}");
        assertThatThrownBy(() -> callback(prepTurn, "registrarCurso")
                .call("{\"nombre\":\"Algoritmos\",\"dia\":\"Lunes\",\"horaInicio\":\"08:00\",\"horaFin\":\"10:00\"}"))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("ONE_OPERATION_PER_TURN"));
        assertThat(prepTurn.response("").message()).contains("Cambiar rol a CONDUCTOR");
    }

    private ToolCallback callback(AgentToolRegistry.Turn turn, String name) {
        return Arrays.stream(turn.callbacks()).filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }
}
