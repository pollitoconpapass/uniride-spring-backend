package com.uniride.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RechazarMultipleRequest {

    @NotEmpty(message = "Debes indicar al menos una solicitud")
    private List<Long> ids;

    private String motivo;
}
