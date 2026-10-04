package com.uniride.dto.responses;

import java.util.Map;

public record ErrorRespuesta(
        String mensaje,
        Map<String, String> campos) {
}
