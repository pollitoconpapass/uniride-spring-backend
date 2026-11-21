package com.uniride.agent.dto;

/** A partial provider failure does not invalidate a receipt already produced by the backend. */
public record AdvertenciaAgente(String codigo, String mensaje) { }
