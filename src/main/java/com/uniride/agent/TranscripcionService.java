package com.uniride.agent;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Transcribes request audio in memory. Audio and transcripts are never stored or logged. */
@Service
public class TranscripcionService {

    static final long MAX_BYTES = 2L * 1024 * 1024;
    static final long MIN_BYTES = 1024;

    private static final Map<String, String> FORMATOS = Map.ofEntries(
            Map.entry("audio/webm", "webm"), Map.entry("video/webm", "webm"),
            Map.entry("audio/ogg", "ogg"), Map.entry("application/ogg", "ogg"),
            Map.entry("audio/mp4", "m4a"), Map.entry("audio/m4a", "m4a"), Map.entry("audio/x-m4a", "m4a"),
            Map.entry("audio/wav", "wav"), Map.entry("audio/x-wav", "wav"), Map.entry("audio/wave", "wav"),
            Map.entry("audio/mpeg", "mp3"), Map.entry("audio/mp3", "mp3"));

    private final TranscriptionModel modelo;

    public TranscripcionService(TranscriptionModel modelo) {
        this.modelo = modelo;
    }

    public String transcribir(MultipartFile audio) {
        String extension = validar(audio);
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw invalid("No se pudo leer el audio.");
        }
        String texto;
        try {
            texto = modelo.transcribe(new Archivo(bytes, "voz." + extension));
        } catch (RuntimeException failure) {
            throw traducir(failure);
        }
        if (texto == null || texto.isBlank()) {
            throw new AgentProviderException(HttpStatus.BAD_REQUEST, "VOICE_NOT_UNDERSTOOD",
                    "No se entendió el audio. Intenta de nuevo.");
        }
        texto = texto.strip();
        AgentConversationStore.validateMessage(texto);
        return texto;
    }

    private static String validar(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw invalid("El audio es obligatorio.");
        if (audio.getSize() > MAX_BYTES)
            throw new AgentProviderException(HttpStatus.PAYLOAD_TOO_LARGE, "VOICE_TOO_LARGE",
                    "El audio supera los 2 MB. Envía una grabación más corta.");
        if (audio.getSize() < MIN_BYTES) throw invalid("El audio es demasiado corto.");
        String tipo = audio.getContentType() == null ? ""
                : audio.getContentType().split(";")[0].strip().toLowerCase(Locale.ROOT);
        String extension = FORMATOS.get(tipo);
        if (extension == null) extension = extension(audio.getOriginalFilename());
        if (extension == null)
            throw new AgentProviderException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VOICE_UNSUPPORTED_FORMAT",
                    "Formato de audio no soportado. Usa webm, ogg, m4a, wav o mp3.");
        return extension;
    }

    private static String extension(String nombre) {
        if (nombre == null) return null;
        int punto = nombre.lastIndexOf('.');
        if (punto < 0) return null;
        return switch (nombre.substring(punto + 1).toLowerCase(Locale.ROOT)) {
            case "webm" -> "webm";
            case "ogg" -> "ogg";
            case "mp4", "m4a" -> "m4a";
            case "wav" -> "wav";
            case "mp3" -> "mp3";
            default -> null;
        };
    }

    private static AgentProviderException invalid(String mensaje) {
        return new AgentProviderException(HttpStatus.BAD_REQUEST, "VOICE_INVALID_AUDIO", mensaje);
    }

    private static AgentProviderException traducir(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AgentProviderException controlada) return controlada;
            if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.http.HttpTimeoutException)
                return new AgentProviderException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT",
                        "El proveedor de voz superó el tiempo de espera.");
            if (cause instanceof OpenAIServiceException provider) {
                if (provider.statusCode() == 429) return new AgentProviderException(HttpStatus.TOO_MANY_REQUESTS,
                        "PROVIDER_RATE_LIMIT", "El proveedor alcanzó su límite de uso. Intenta más tarde.");
                if (provider.statusCode() == 401 || provider.statusCode() == 403 || provider.statusCode() >= 500)
                    return new AgentProviderException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                            "El servicio de voz no está disponible temporalmente.");
                return new AgentProviderException(HttpStatus.BAD_GATEWAY, "PROVIDER_REJECTED",
                        "El proveedor rechazó el audio. Intenta de nuevo.");
            }
            if (cause instanceof OpenAIIoException) return new AgentProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE", "No se pudo contactar al proveedor de voz.");
        }
        return new AgentProviderException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_UNAVAILABLE",
                "No se pudo transcribir el audio. Intenta de nuevo.");
    }

    private static final class Archivo extends ByteArrayResource {
        private final String nombre;

        private Archivo(byte[] datos, String nombre) {
            super(datos);
            this.nombre = nombre;
        }

        @Override
        public String getFilename() {
            return nombre;
        }
    }
}
