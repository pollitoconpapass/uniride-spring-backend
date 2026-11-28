package com.uniride.agent.app.dto;

import com.uniride.agent.app.confirmation.EstadoConfirmacion;
import com.uniride.agent.app.confirmation.TipoOperacion;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ConfirmacionResumen(UUID id, TipoOperacion tipo, EstadoConfirmacion estado,
        String resumen, JsonNode parametros, Instant expira, OperacionResumen resultado) { }
