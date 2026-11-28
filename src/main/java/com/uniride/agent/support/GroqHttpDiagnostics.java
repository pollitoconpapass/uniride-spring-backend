package com.uniride.agent.support;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.openai.metadata.OpenAiRateLimit;
import org.springframework.stereotype.Component;

/** Counts the synchronous chat transport's requests inside the current turn. */
@Component
public final class GroqHttpDiagnostics implements OpenAiHttpClientBuilderCustomizer {
    private static final Logger LOG = LoggerFactory.getLogger(GroqHttpDiagnostics.class);
    private static final ThreadLocal<Scope> ACTUAL = new ThreadLocal<>();
    private static final List<String> RATE_HEADERS = List.of(
            "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests",
            "x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens");
    private static final RateLimit LIMITES_DESCONOCIDOS = new OpenAiRateLimit(null, null, null, null, null, null);

    public static Scope open() {
        Scope scope = new Scope(ACTUAL.get());
        ACTUAL.set(scope);
        return scope;
    }

    @Override
    public void customize(SpringAiOpenAiHttpClient.Builder builder) {
        builder.interceptor(GroqHttpDiagnostics::intercept);
    }

    private static Response intercept(Interceptor.Chain chain) throws IOException {
        var request = chain.request();
        Scope scope = ACTUAL.get();
        if (scope == null || !request.method().equals("POST")
                || !request.url().encodedPath().endsWith("/chat/completions")) {
            return chain.proceed(request);
        }

        scope.rounds++;
        scope.status = null;
        Response response = chain.proceed(request);
        scope.status = response.code();
        return response;
    }

    public static final class Scope implements AutoCloseable {
        private final Scope anterior;
        private int rounds;
        private Integer status;
        private RateLimitDetails rateLimit;

        private Scope(Scope anterior) {
            this.anterior = anterior;
        }

        int rounds() { return rounds; }

        public void recordRateLimit(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof OpenAIServiceException provider && provider.statusCode() == 429) {
                    status = 429;
                    rateLimit = details(provider);
                    return;
                }
            }
        }

        @Override
        public void close() {
            if (anterior == null) ACTUAL.remove();
            else ACTUAL.set(anterior);

            if (rateLimit != null) {
                RateLimit limits = rateLimit.limits();
                LOG.warn("Groq chat estadoHttp={} rondasHttp={} tipoLimite={} retryAfterSeconds={} "
                                + "x-ratelimit-limit-requests={} x-ratelimit-remaining-requests={} "
                                + "x-ratelimit-reset-requests={} x-ratelimit-limit-tokens={} "
                                + "x-ratelimit-remaining-tokens={} x-ratelimit-reset-tokens={}",
                        status, rounds, rateLimit.type(), rateLimit.retryAfterSeconds(),
                        noNegativo(limits.getRequestsLimit()), noNegativo(limits.getRequestsRemaining()),
                        noNegativo(limits.getRequestsReset()), noNegativo(limits.getTokensLimit()),
                        noNegativo(limits.getTokensRemaining()), noNegativo(limits.getTokensReset()));
            } else {
                LOG.debug("Groq chat estadoHttp={} rondasHttp={}", status, rounds);
            }
        }
    }

    private static RateLimitDetails details(OpenAIServiceException provider) {
        Headers originales = provider.headers();
        var headers = Headers.builder();
        for (String name : RATE_HEADERS) {
            String value = cabecera(originales, name);
            if (value != null) headers.put(name, value);
        }
        RateLimit limites = OpenAiRateLimit.from(headers.build());
        return new RateLimitDetails(tipo(provider), retryAfter(originales),
                limites instanceof EmptyRateLimit ? LIMITES_DESCONOCIDOS : limites);
    }

    private static String cabecera(Headers headers, String name) {
        List<String> values = headers.values(name);
        if (values.isEmpty() || values.getFirst().length() > 64) return null;
        return values.getFirst().strip();
    }

    private static Long retryAfter(Headers headers) {
        String raw = cabecera(headers, "retry-after");
        if (raw == null) return null;
        try {
            return noNegativo(Long.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Tipo tipo(OpenAIServiceException provider) {
        try {
            return switch (provider.type().orElse("").strip().toLowerCase(Locale.ROOT)) {
                case "tpm", "tokens_per_minute" -> Tipo.TPM;
                case "tpd", "tokens_per_day" -> Tipo.TPD;
                case "rpm", "requests_per_minute" -> Tipo.RPM;
                case "rpd", "requests_per_day" -> Tipo.RPD;
                case "tokens" -> Tipo.TOKENS;
                case "requests" -> Tipo.REQUESTS;
                default -> Tipo.DESCONOCIDO;
            };
        } catch (OpenAIInvalidDataException invalid) {
            return Tipo.DESCONOCIDO;
        }
    }

    private static Long noNegativo(Long valor) {
        return valor != null && valor >= 0 ? valor : null;
    }

    private static Duration noNegativo(Duration valor) {
        return valor != null && !valor.isNegative() ? valor : null;
    }

    private record RateLimitDetails(Tipo type, Long retryAfterSeconds, RateLimit limits) { }

    private enum Tipo { TPM, TPD, RPM, RPD, TOKENS, REQUESTS, DESCONOCIDO }
}
