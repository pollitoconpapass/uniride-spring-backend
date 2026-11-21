package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.Rol;
import com.uniride.services.RutaService;
import com.uniride.services.UsuarioService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class RutaAgentToolsTest {

    @Mock RutaService rutaService;
    @Mock UsuarioService usuarioService;
    @InjectMocks RutaAgentTools tools;

    @Test
    void consultarMisRutasDevuelveActivasYVencidas() {
        when(rutaService.listarMisRutas(null, 0, 100)).thenReturn(new PageImpl<>(List.of(
                ruta(1L, EstadoRuta.ACTIVA), ruta(2L, EstadoRuta.VENCIDA))));

        List<RutaResumen> resultado = tools.consultarMisRutas();

        assertThat(resultado).extracting(RutaResumen::estado)
                .containsExactly(EstadoRuta.ACTIVA, EstadoRuta.VENCIDA);
    }

    @Test
    void crearRutaPublicaConLosDatosIndicados() {
        when(usuarioService.usuarioActual()).thenReturn(
                Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(rutaService.publicar(any(RutaRequest.class))).thenReturn(new RutaRespuesta(
                3L, "Surco", "UPC", "Parque", null, null, 4,
                EstadoRuta.ACTIVA, LocalDateTime.now(), 2L, "Juan"));

        OperacionResumen resultado = tools.crearRuta(
                "Surco", 4, "Parque", null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(resultado.id()).isEqualTo(3L);
        ArgumentCaptor<RutaRequest> captor = ArgumentCaptor.forClass(RutaRequest.class);
        verify(rutaService).publicar(captor.capture());
        assertThat(captor.getValue().origen()).isEqualTo("Surco");
        assertThat(captor.getValue().capacidadMaxima()).isEqualTo(4);
        assertThat(captor.getValue().puntosReferencia()).isEqualTo("Parque");
    }

    @Test
    void crearRutaRechazaAlPasajero() {
        when(usuarioService.usuarioActual()).thenReturn(
                Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build());

        OperacionResumen resultado = tools.crearRuta("Surco", 4, "Parque", null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(rutaService);
    }

    private RutaRespuesta ruta(Long id, EstadoRuta estado) {
        return new RutaRespuesta(id, "Surco", "UPC", "Parque", null, null, 4,
                estado, LocalDateTime.now(), 2L, "Jose");
    }
}
