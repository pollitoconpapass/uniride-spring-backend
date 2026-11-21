package com.uniride.agent.dto;

import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.confirmation.TipoOperacion;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ConfirmacionResumen(UUID id, TipoOperacion tipo, EstadoConfirmacion estado,
        String resumen, JsonNode parametros, Instant expira, OperacionResumen resultado) { }
