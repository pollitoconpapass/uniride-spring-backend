package com.uniride.agent.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum TipoCampoAclaracion {
    TEXT, TEXTAREA, NUMBER, DATE, TIME, SELECT;

    @JsonValue public String valor() { return name().toLowerCase(Locale.ROOT); }
}
