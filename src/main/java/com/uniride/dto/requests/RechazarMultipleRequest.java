package com.uniride.dto.requests;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record RechazarMultipleRequest(
        @NotEmpty(message = "Debes indicar al menos una solicitud")
        List<Long> ids,
        String motivo) {
}
