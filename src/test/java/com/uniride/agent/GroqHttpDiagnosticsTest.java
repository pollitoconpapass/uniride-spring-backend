package com.uniride.agent;

import com.uniride.agent.support.GroqHttpDiagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GroqHttpDiagnosticsTest {
    @Test
    void registraSoloCamposTecnicosDeUn429Anidado(CapturedOutput output) {
        var headers = Headers.builder()
                .put("Retry-After", "2")
                .put("X-RateLimit-Limit-Requests", "14400")
                .put("X-RateLimit-Remaining-Requests", "14370")
                .put("X-RateLimit-Reset-Requests", "2m59.56s")
                .put("X-RateLimit-Limit-Tokens", "18000")
                .put("X-RateLimit-Remaining-Tokens", "0")
                .put("X-RateLimit-Reset-Tokens", "7.66s")
                .put("Authorization", "valor-privado")
                .build();

        try (var diagnostico = GroqHttpDiagnostics.open()) {
            diagnostico.recordRateLimit(new IllegalStateException("contenido-del-usuario",
                    limit(headers, "tokens_per_minute")));
        }

        assertThat(output.getAll()).contains("estadoHttp=429", "rondasHttp=0", "tipoLimite=TPM", "retryAfterSeconds=2",
                "x-ratelimit-limit-requests=14400", "x-ratelimit-remaining-requests=14370",
                "x-ratelimit-reset-requests=PT2M59.56S", "x-ratelimit-limit-tokens=18000",
                "x-ratelimit-remaining-tokens=0", "x-ratelimit-reset-tokens=PT7.66S")
                .doesNotContain("contenido-del-usuario", "mensaje-del-proveedor", "valor-privado", "Authorization");
    }

    @Test
    void ignoraTiposYValoresArbitrariosDelProveedor(CapturedOutput output) {
        var headers = Headers.builder().put("retry-after", "detalle-privado")
                .put("x-ratelimit-remaining-tokens", "-2")
                .put("x-ratelimit-reset-tokens", "1s".repeat(100))
                .build();

        try (var diagnostico = GroqHttpDiagnostics.open()) {
            diagnostico.recordRateLimit(limit(headers, "tipo-privado"));
        }

        assertThat(output.getAll()).contains("estadoHttp=429", "tipoLimite=DESCONOCIDO", "retryAfterSeconds=null",
                "x-ratelimit-remaining-tokens=null", "x-ratelimit-reset-tokens=null")
                .doesNotContain("detalle-privado", "tipo-privado", "mensaje-del-proveedor", "1s1s");
    }

    @Test
    void distingueAusenciaDeCuotaDeUnaCuotaAgotada(CapturedOutput output) {
        try (var diagnostico = GroqHttpDiagnostics.open()) {
            diagnostico.recordRateLimit(RateLimitException.builder().headers(Headers.builder().build()).build());
        }

        assertThat(output.getAll()).contains("tipoLimite=DESCONOCIDO", "x-ratelimit-remaining-requests=null",
                "x-ratelimit-remaining-tokens=null");
    }

    private static RateLimitException limit(Headers headers, String type) {
        return RateLimitException.builder().headers(headers).error(ErrorObject.builder()
                .code("rate_limit_exceeded").param(Optional.empty()).type(type).message("mensaje-del-proveedor").build())
                .build();
    }
}
