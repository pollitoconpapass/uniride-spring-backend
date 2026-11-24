package com.uniride.agent;

import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.exceptions.CamposInvalidosException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Wire format carried inside the existing chat message. A submission belongs to one active form. */
final class EnvioAclaracion {
    private static final Pattern PREFIJO = Pattern.compile("(?iu)^datos de aclaraci[oó]n\\b");
    private static final Pattern SOBRE = Pattern.compile("(?isu)^datos de aclaraci[oó]n ([0-9a-f-]{36}):\\s*(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private EnvioAclaracion() { }

    static Map<String, String> leer(String mensaje, AclaracionAgente activa) {
        if (!PREFIJO.matcher(mensaje.strip()).find()) return null;
        var match = SOBRE.matcher(mensaje.strip());
        if (!match.matches() || activa == null || !activa.id().toString().equalsIgnoreCase(match.group(1))) {
            throw new AgentProviderException(HttpStatus.CONFLICT, "CLARIFICATION_EXPIRED",
                    "Esta aclaración ya no está activa. Vuelve a indicar qué quieres hacer.");
        }
        JsonNode datos;
        try {
            datos = JSON.readTree(match.group(2));
        } catch (RuntimeException formato) {
            throw invalido();
        }
        if (!datos.isObject() || datos.isEmpty() || datos.size() > activa.campos().size()) throw invalido();
        var campos = new LinkedHashMap<String, String>();
        for (var entrada : datos.properties()) {
            var campo = activa.campos().stream().filter(c -> c.id().equals(entrada.getKey())).findFirst()
                    .orElseThrow(EnvioAclaracion::invalido);
            campos.put(campo.id(), validar(campo, entrada.getValue()));
        }
        return Map.copyOf(campos);
    }

    private static String validar(CampoAclaracion campo, JsonNode dato) {
        if (!dato.isString() && !(dato.isIntegralNumber() && dato.canConvertToInt())) throw invalido();
        String texto = dato.asText().strip();
        AgentConversationStore.validateNoSecrets(texto);
        if (texto.isBlank()) throw invalido();
        try {
            return switch (campo.tipo()) {
                case NUMBER -> {
                    int numero = Integer.parseInt(texto);
                    if (campo.minimo() != null && numero < campo.minimo()
                            || campo.maximo() != null && numero > campo.maximo()) throw invalido();
                    yield Integer.toString(numero);
                }
                case DATE -> LocalDate.parse(texto).toString();
                case TIME -> LocalTime.parse(texto).toString();
                case SELECT -> campo.opciones().stream().filter(o -> o.valor().equals(texto)).findFirst()
                        .orElseThrow(EnvioAclaracion::invalido).valor();
                case TEXT, TEXTAREA -> {
                    if (!dato.isString() || campo.longitudMaxima() != null && texto.length() > campo.longitudMaxima())
                        throw invalido();
                    yield texto;
                }
            };
        } catch (java.time.DateTimeException | NumberFormatException formato) {
            throw invalido();
        }
    }

    static String paraHistorial(AclaracionAgente esquema, Map<String, String> campos) {
        return esquema.campos().stream().filter(c -> campos.containsKey(c.id()))
                .map(c -> c.etiqueta() + ": " + valorParaHistorial(c, campos.get(c.id())))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String valorParaHistorial(CampoAclaracion campo, String valor) {
        if (!"ruta".equals(campo.id())) return valor;
        return campo.opciones().stream().filter(opcion -> opcion.valor().equals(valor)).findFirst()
                .orElseThrow(EnvioAclaracion::invalido).etiqueta();
    }

    private static CamposInvalidosException invalido() {
        return new CamposInvalidosException("Revisa los campos de la aclaración e intenta de nuevo.");
    }
}
