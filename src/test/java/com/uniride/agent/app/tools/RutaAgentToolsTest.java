package com.uniride.agent.app.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.uniride.agent.app.AgentQueryService;
import com.uniride.agent.app.confirmation.ConfirmationService;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.dto.EstadoOperacion;
import com.uniride.agent.app.dto.OpcionAclaracion;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.agent.app.dto.PaginaAgente;
import com.uniride.agent.app.dto.RutaResumen;
import com.uniride.agent.app.dto.TipoCampoAclaracion;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.*;
import com.uniride.services.UsuarioService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RutaAgentToolsTest {
    @Mock AgentQueryService consultas;
    @Mock ConfirmationService confirmaciones;
    @Mock UsuarioService usuarioService;
    @InjectMocks RutaAgentTools tools;

    @Test
    void consultarMisRutasDevuelveActivasYVencidas() {
        when(consultas.rutas(null)).thenReturn(new PaginaAgente<>(List.of(
                ruta(1L, EstadoRuta.ACTIVA), ruta(2L, EstadoRuta.VENCIDA)), 0, 10, 2, false));
        assertThat(tools.consultarMisRutas()).extracting(RutaResumen::estado)
                .containsExactly(EstadoRuta.ACTIVA, EstadoRuta.VENCIDA);
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void crearRutaPreparaConLosDatosIndicadosSinPublicar() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        OperacionResumen pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma la ruta.");
        when(confirmaciones.prepararRuta(any())).thenReturn(pendiente);

        assertThat(tools.crearRuta("Surco", 4, "Parque", null, null)).isSameAs(pendiente);
        var captor = ArgumentCaptor.forClass(RutaRequest.class);
        verify(confirmaciones).prepararRuta(captor.capture());
        assertThat(captor.getValue().origen()).isEqualTo("Surco");
        assertThat(captor.getValue().capacidadMaxima()).isEqualTo(4);
        assertThat(captor.getValue().puntosReferencia()).isEqualTo("Parque");
        verifyNoInteractions(consultas);
    }

    @Test
    void crearRutaRechazaAlPasajero() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build());
        assertThat(tools.crearRuta("Surco", 4, "Parque", null, null).estado()).isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void datosFaltantesNuncaPreparanConfirmacion() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        var todos = tools.crearRuta(null, null, null, null, null);
        var conOrigen = tools.crearRuta("Surco", null, null, null, null);
        var conOrigenYReferencias = tools.crearRuta("Surco", null, "Parque", null, null);

        assertThat(todos.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(todos.aclaracion().campos()).extracting(CampoAclaracion::id)
                .containsExactly("origen", "referencias", "capacidad");
        assertThat(conOrigen.aclaracion().campos()).extracting(CampoAclaracion::id)
                .containsExactly("referencias", "capacidad");
        assertThat(conOrigen.detalle()).doesNotContain("origen");
        assertThat(conOrigenYReferencias.aclaracion().campos()).extracting(CampoAclaracion::id)
                .containsExactly("capacidad");
        verifyNoInteractions(confirmaciones, consultas);
    }

    @Test
    void capacidadInvalidaEsElUnicoCampoSolicitado() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());

        var campo = tools.crearRuta("Surco", 0, "Parque", null, null).aclaracion().campos().getFirst();

        assertThat(campo.id()).isEqualTo("capacidad");
        assertThat(campo.minimo()).isEqualTo(1);
        assertThat(campo.obligatorio()).isTrue();
        assertThat(campo.inicial()).isNull();
        verifyNoInteractions(confirmaciones, consultas);
    }

    @Test
    void paginaAdicionalConservaMetadatos() {
        var page = new PaginaAgente<>(List.of(ruta(11L, EstadoRuta.ACTIVA)), 1, 10, 21, true);
        when(consultas.rutas(1)).thenReturn(page);
        assertThat(tools.consultarMisRutas(1)).isSameAs(page);
        assertThat(tools.consultarMisRutas(1).hayMas()).isTrue();
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void editarRutaRechazaAlPasajero() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.PASAJERO).build());
        assertThat(tools.editarRuta("Surco", null, 3, null, null, null, null).estado())
                .isEqualTo(EstadoOperacion.ERROR);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void editarRutaExigeIndicarElCambio() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        var resultado = tools.editarRuta("Surco", null, null, null, null, null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        assertThat(resultado.aclaracion().alMenosUno()).containsExactly("nuevoOrigen", "capacidad", "referencias",
                "zonasSinParada", "notaContribucion");
        assertThat(resultado.aclaracion().campos()).noneMatch(CampoAclaracion::obligatorio);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test
    void editarRutaNoPreparaSiHayVariasCoincidencias() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(consultas.identificarRutas("Surco", null)).thenReturn(
                new PageImpl<>(List.of(rutaRespuesta(1L), rutaRespuesta(2L)), PageRequest.of(0, 2), 2));
        var resultado = tools.editarRuta("Surco", null, 3, null, null, null, null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        assertThat(resultado.aclaracion()).isNull();
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void eliminarRutaAceptaOrigenODestinoYExponeOpcionesAutorizadas() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(consultas.identificarRutas(null, "UPC")).thenReturn(new PageImpl<>(List.of(
                rutaRespuesta(9L, "Surco"), rutaRespuesta(11L, "Miraflores"))));

        var selector = tools.eliminarRuta(null, null).aclaracion();
        assertThat(selector.alMenosUno()).containsExactly("origenActual", "destinoActual");
        assertThat(selector.campos()).noneMatch(CampoAclaracion::obligatorio);

        var ambiguo = tools.eliminarRuta(null, "UPC");
        assertThat(ambiguo.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        assertThat(ambiguo.aclaracion().campos()).singleElement().satisfies(campo -> {
            assertThat(campo.id()).isEqualTo("origenActual");
            assertThat(campo.tipo()).isEqualTo(TipoCampoAclaracion.SELECT);
            assertThat(campo.opciones()).extracting(OpcionAclaracion::valor).containsExactly("Surco", "Miraflores");
        });
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void coincidenciasParcialesNoSePresentanComoOpcionesUnivocas() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(consultas.identificarRutas("Surco", null)).thenReturn(new PageImpl<>(List.of(
                rutaRespuesta(9L, "Surco"), rutaRespuesta(11L, "Surco Norte"))));

        var resultado = tools.eliminarRuta("Surco", null);

        assertThat(resultado.estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        assertThat(resultado.aclaracion().campos()).singleElement().satisfies(campo -> {
            assertThat(campo.tipo()).isEqualTo(TipoCampoAclaracion.TEXT);
            assertThat(campo.opciones()).isNull();
        });
        verifyNoInteractions(confirmaciones);
    }

    @Test
    void editarRutaPreparaSoloLosCambiosIndicados() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(consultas.identificarRutas("Surco", "UPC")).thenReturn(new PageImpl<>(List.of(rutaRespuesta(9L))));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma la edición.");
        when(confirmaciones.prepararEdicionRuta(9L, null, 3, null, null, null)).thenReturn(pendiente);

        assertThat(tools.editarRuta("Surco", "UPC", 3, null, null, null, null)).isSameAs(pendiente);
    }

    @Test
    void eliminarRutaPreparaLaCoincidenciaUnica() {
        when(usuarioService.usuarioActual()).thenReturn(Usuario.builder().id(2L).rolPrincipal(Rol.CONDUCTOR).build());
        when(consultas.identificarRutas(null, "UPC")).thenReturn(new PageImpl<>(List.of(rutaRespuesta(9L))));
        var pendiente = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma la eliminación.");
        when(confirmaciones.prepararEliminacionRuta(9L)).thenReturn(pendiente);

        assertThat(tools.eliminarRuta(null, "UPC")).isSameAs(pendiente);
    }

    private RutaResumen ruta(Long id, EstadoRuta estado) {
        return new RutaResumen(id, "Surco", "UPC", "Parque", null, 4, estado);
    }

    private RutaRespuesta rutaRespuesta(Long id) {
        return rutaRespuesta(id, "Surco");
    }

    private RutaRespuesta rutaRespuesta(Long id, String origen) {
        return new RutaRespuesta(id, origen, "UPC", "Parque", null, null, 4,
                EstadoRuta.ACTIVA, java.time.LocalDateTime.now(), 2L, "Ana");
    }
}
