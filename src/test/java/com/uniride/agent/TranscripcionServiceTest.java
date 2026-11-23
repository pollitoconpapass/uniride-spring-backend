package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class TranscripcionServiceTest {

    private final TranscriptionModel modelo = mock(TranscriptionModel.class);
    private final TranscripcionService service = new TranscripcionService(modelo);

    @Test
    void transcribesValidAudioWithItsRealExtension() {
        var recurso = new AtomicReference<Resource>();
        when(modelo.transcribe(any(Resource.class))).thenAnswer(invocation -> {
            recurso.set(invocation.getArgument(0));
            return "  ¿Cuáles son mis rutas?  ";
        });
        String texto = service.transcribir(audio("audio/webm;codecs=opus", "voz.webm", 4096));

        assertThat(texto).isEqualTo("¿Cuáles son mis rutas?");
        assertThat(recurso.get().getFilename()).isEqualTo("voz.webm");
    }

    @Test
    void acceptsFilenameWhenBrowserSendsOctetStream() {
        when(modelo.transcribe(any(Resource.class))).thenReturn("mis viajes");
        assertThat(service.transcribir(audio("application/octet-stream", "voz.m4a", 4096)))
                .isEqualTo("mis viajes");
    }

    @Test
    void rejectsEmptyAudio() {
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 0)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_INVALID_AUDIO"));
        verify(modelo, never()).transcribe(any(Resource.class));
    }

    @Test
    void rejectsAudioUnderTheMinimum() {
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 512)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_INVALID_AUDIO"));
    }

    @Test
    void rejectsAudioOverTwoMegabytes() {
        assertThatThrownBy(() -> service.transcribir(
                audio("audio/webm", "voz.webm", (int) TranscripcionService.MAX_BYTES + 1)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_TOO_LARGE"));
        verify(modelo, never()).transcribe(any(Resource.class));
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> service.transcribir(audio("text/plain", "notas.txt", 2048)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_UNSUPPORTED_FORMAT"));
        verify(modelo, never()).transcribe(any(Resource.class));
    }

    @Test
    void rejectsBlankTranscription() {
        when(modelo.transcribe(any(Resource.class))).thenReturn("   ");
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 4096)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_NOT_UNDERSTOOD"));
    }

    @Test
    void appliesTheSameMessageLimitsAsTextChat() {
        when(modelo.transcribe(any(Resource.class))).thenReturn("a".repeat(1001));
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 4096)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_MESSAGE"));
    }

    @Test
    void rejectsSecretsInTranscription() {
        when(modelo.transcribe(any(Resource.class))).thenReturn("mi clave es GROQ_API_KEY=testsecret");
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 4096)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("SENSITIVE_MESSAGE"));
    }

    @Test
    void mapsProviderFailureToAControlledError() {
        when(modelo.transcribe(any(Resource.class))).thenThrow(new IllegalStateException("socket closed"));
        assertThatThrownBy(() -> service.transcribir(audio("audio/webm", "voz.webm", 4096)))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo("VOICE_UNAVAILABLE"));
    }

    private static MultipartFile audio(String tipo, String nombre, int bytes) {
        return new MockMultipartFile("audio", nombre, tipo, new byte[bytes]);
    }
}
