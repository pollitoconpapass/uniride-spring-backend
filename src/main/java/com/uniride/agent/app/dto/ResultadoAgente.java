package com.uniride.agent.app.dto;

import tools.jackson.databind.JsonNode;

/** Extensible envelope. Data is captured from Java tools, never copied from model output. */
public record ResultadoAgente(String tipo, JsonNode datos) { }
