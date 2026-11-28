package com.uniride.agent.core;

/**
 * Describe un agente ante el modelo: nombre, alcance y sus tools.
 * La descripción se publica en la sección «Temas» del prompt de sistema.
 */
public record AgentDefinition(String nombre, String descripcion, Object... herramientas) {
}
