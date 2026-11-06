package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.uniride.dto.requests.*;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.*;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.security.JwtService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthHardeningTest {
    UsuarioRepository usuarios = mock(UsuarioRepository.class);
    NotificacionService avisos = mock(NotificacionService.class);
    BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder());
    AuthService servicio = new AuthService(usuarios, encoder, mock(JwtService.class), avisos,
            mock(UsuarioMapper.class), mock(MensajeService.class), mock(PerfilRolService.class));
    LocalDateTime ahora = LocalDateTime.of(2026, 11, 5, 10, 0);
    Usuario usuario = Usuario.builder().id(1L).correoInstitucional("ana@upc.edu.pe")
            .codigoVerificacion(encoder.encode("123456")).codigoVerificacionExpiracion(ahora.plusMinutes(15)).build();

    void reloj(LocalDateTime fecha) {
        ReflectionTestUtils.setField(servicio, "clock", Clock.fixed(
                fecha.atZone(com.uniride.config.TiempoConfig.LIMA).toInstant(), com.uniride.config.TiempoConfig.LIMA));
    }
    @BeforeEach void preparar() {
        reloj(ahora);
        when(usuarios.bloquearPorCorreo("ana@upc.edu.pe")).thenReturn(Optional.of(usuario));
    }
    @Test void cincoFallosPersistenYBloqueanVerificacionYReenvio() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> servicio.verificarCodigo(new VerificarCodigoRequest(" ANA@UPC.EDU.PE ", "000000")))
                    .isInstanceOf(CamposInvalidosException.class);
        }
        assertThat(usuario.getIntentosOtp()).isEqualTo(5);
        assertThat(usuario.getOtpBloqueadoHasta()).isEqualTo(ahora.plusMinutes(15));
        assertThatThrownBy(() -> servicio.verificarCodigo(new VerificarCodigoRequest("ana@upc.edu.pe", "123456")))
                .isInstanceOf(DemasiadasSolicitudesException.class);
        assertThatThrownBy(() -> servicio.reenviarCodigo(new ReenviarCodigoRequest("ana@upc.edu.pe")))
                .isInstanceOf(DemasiadasSolicitudesException.class);
        verify(usuarios, times(5)).save(usuario);
        verifyNoInteractions(avisos);
    }
    @Test void codigoExpiraEnElInstanteExacto() {
        reloj(ahora.plusMinutes(15));
        assertThatThrownBy(() -> servicio.verificarCodigo(new VerificarCodigoRequest("ana@upc.edu.pe", "123456")))
                .isInstanceOf(BusinessException.class);
        assertThat(usuario.isCuentaVerificada()).isFalse();
    }
    @Test void codigoValidoHasheadoVerificaYSeBorra() {
        servicio.verificarCodigo(new VerificarCodigoRequest("ana@upc.edu.pe", "123456"));
        assertThat(usuario.isCuentaVerificada()).isTrue();
        assertThat(usuario.getCodigoVerificacion()).isNull();
    }
    @Test void reenviarNoExponeUsuarioNiAlmacenaCodigoEnClaroYRespetaCooldown() {
        var respuesta = servicio.reenviarCodigo(new ReenviarCodigoRequest("ana@upc.edu.pe"));
        assertThat(respuesta.usuario()).isNull();
        assertThat(usuario.getCodigoVerificacion()).startsWith("$2");
        assertThatThrownBy(() -> servicio.reenviarCodigo(new ReenviarCodigoRequest("ana@upc.edu.pe")))
                .isInstanceOf(DemasiadasSolicitudesException.class);
        verify(avisos, times(1)).notificarCodigo(eq(usuario), any(), any());
    }
}
