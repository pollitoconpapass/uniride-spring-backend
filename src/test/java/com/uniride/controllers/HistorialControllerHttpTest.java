package com.uniride.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uniride.config.TiempoConfig;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.repositories.ViajeRepository;
import com.uniride.security.JwtAuthFilter;
import com.uniride.security.JwtService;
import com.uniride.security.LimiteAutenticacion;
import com.uniride.security.SecurityConfig;
import com.uniride.security.UsuarioDetailsService;
import com.uniride.services.EstadisticasService;
import com.uniride.services.MensajeService;
import com.uniride.services.PerfilRolService;
import com.uniride.services.UsuarioService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exercises the unchanged authenticated endpoint and actual PDF generator with in-memory fixtures. */
@SpringJUnitConfig(HistorialControllerHttpTest.Configuration.class)
@WebAppConfiguration
class HistorialControllerHttpTest {
    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private static final List<EstadoSolicitud> ESTADOS_HISTORICOS =
            List.of(EstadoSolicitud.ACEPTADA, EstadoSolicitud.CANCELADA);
    private static final Usuario ANA = usuario(7L, "Ana", Rol.PASAJERO);
    private static final Usuario BRUNO = usuario(8L, "Bruno", Rol.CONDUCTOR);

    @Autowired WebApplicationContext context;
    @Autowired UsuarioRepository usuarios;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PenalidadRepository penalidades;
    @Autowired JwtService jwt;
    private MockMvc mvc;

    @DynamicPropertySource
    static void jwtDePrueba(DynamicPropertyRegistry properties) {
        properties.add("uniride.jwt.secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        properties.add("uniride.jwt.expiracion-ms", () -> 3600000);
    }

    @BeforeEach
    void configurar() {
        reset(usuarios, viajes, solicitudes, penalidades);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void descargaPdfConFechasInclusivasYExcluyeViajesProgramadosFuturos() throws Exception {
        registrar(ANA);
        LocalDate desde = LocalDate.of(2026, 11, 1);
        when(viajes.historialComoConductor(ANA.getId(), EstadoViaje.PROGRAMADO, TODAY)).thenReturn(List.of(
                viaje(1L, ANA, "Antes del rango", desde.minusDays(1), 9, EstadoViaje.COMPLETADO),
                viaje(2L, ANA, "Inicio del rango", desde, 9, EstadoViaje.COMPLETADO),
                viaje(3L, ANA, "Límite del rango", TODAY, 10, EstadoViaje.PROGRAMADO),
                viaje(4L, ANA, "Futuro programado", TODAY, 11, EstadoViaje.PROGRAMADO),
                viaje(5L, ANA, "Después del rango", TODAY.plusDays(1), 9, EstadoViaje.CANCELADO)));

        byte[] pdf = descargar(pdf().header(HttpHeaders.AUTHORIZATION, bearer(ANA))
                .param("desde", desde.toString())
                .param("hasta", TODAY.toString())
                .param("tipo", "conductor"));

        assertThat(textoPdf(pdf)).contains("Ana Prueba", "Inicio del rango - UPC", "Límite del rango - UPC",
                        "Del 01/11/2026 al 21/11/2026", "Tipo: conductor")
                .doesNotContain("Antes del rango", "Futuro programado", "Después del rango");
        verify(usuarios, times(2)).findByCorreoInstitucional(ANA.getCorreoInstitucional());
        verify(viajes).historialComoConductor(ANA.getId(), EstadoViaje.PROGRAMADO, TODAY);
        verify(penalidades).findByUsuarioId(ANA.getId());
        verifyNoMoreInteractions(usuarios, viajes, solicitudes, penalidades);
    }

    @Test
    void cadaDescargaUsaSuJwtAunqueLaUrlIncluyaUnIdAjeno() throws Exception {
        registrar(ANA);
        registrar(BRUNO);
        when(solicitudes.historialComoPasajero(ANA.getId(), ESTADOS_HISTORICOS)).thenReturn(List.of(
                solicitud(viaje(6L, BRUNO, "Trayecto de Ana", TODAY, 9, EstadoViaje.COMPLETADO))));
        when(solicitudes.historialComoPasajero(BRUNO.getId(), ESTADOS_HISTORICOS)).thenReturn(List.of(
                solicitud(viaje(7L, ANA, "Trayecto de Bruno", TODAY, 9, EstadoViaje.COMPLETADO))));

        String url = "/api/historial/pdf?tipo=PASAJERO&usuarioId=8";
        byte[] deAna = descargar(get(url).contextPath("/api").header(HttpHeaders.AUTHORIZATION, bearer(ANA)));
        byte[] deBruno = descargar(get(url).contextPath("/api").header(HttpHeaders.AUTHORIZATION, bearer(BRUNO)));

        assertThat(textoPdf(deAna)).contains(ANA.getCorreoInstitucional(), "Trayecto de Ana - UPC", "Tipo: pasajero")
                .doesNotContain(BRUNO.getCorreoInstitucional(), "Trayecto de Bruno");
        assertThat(textoPdf(deBruno)).contains(BRUNO.getCorreoInstitucional(), "Trayecto de Bruno - UPC")
                .doesNotContain(ANA.getCorreoInstitucional(), "Trayecto de Ana");
        verify(usuarios, times(2)).findByCorreoInstitucional(ANA.getCorreoInstitucional());
        verify(usuarios, times(2)).findByCorreoInstitucional(BRUNO.getCorreoInstitucional());
        verify(solicitudes).historialComoPasajero(ANA.getId(), ESTADOS_HISTORICOS);
        verify(solicitudes).historialComoPasajero(BRUNO.getId(), ESTADOS_HISTORICOS);
        verify(penalidades).findByUsuarioId(ANA.getId());
        verify(penalidades).findByUsuarioId(BRUNO.getId());
        verifyNoMoreInteractions(usuarios, viajes, solicitudes, penalidades);
    }

    @Test
    void rechazaDescargaSinJwtValidoEnElEncabezado() throws Exception {
        mvc.perform(pdf()).andExpect(status().isForbidden());
        mvc.perform(pdf().header(HttpHeaders.AUTHORIZATION, "Bearer token-invalido"))
                .andExpect(status().isForbidden());
        mvc.perform(pdf().param("token", jwt.generarToken(ANA.getCorreoInstitucional())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(usuarios, viajes, solicitudes, penalidades);
    }

    @ParameterizedTest
    @CsvSource({"2026-02-30,2026-11-21,todos", "2026-11-22,2026-11-21,todos", "2026-11-01,2026-11-21,administrador"})
    void rechazaFiltrosInvalidosAntesDeConsultarElHistorial(String desde, String hasta, String tipo) throws Exception {
        registrar(ANA);

        mvc.perform(pdf().header(HttpHeaders.AUTHORIZATION, bearer(ANA))
                .param("desde", desde).param("hasta", hasta).param("tipo", tipo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        verifyNoInteractions(viajes, solicitudes, penalidades);
    }

    @Test
    void historialVacioDevuelveConflictoSinAdjunto() throws Exception {
        registrar(ANA);

        mvc.perform(pdf().header(HttpHeaders.AUTHORIZATION, bearer(ANA)))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.mensaje").value("No hay historial para exportar"));

        verify(viajes).historialComoConductor(ANA.getId(), EstadoViaje.PROGRAMADO, TODAY);
        verify(solicitudes).historialComoPasajero(ANA.getId(), ESTADOS_HISTORICOS);
        verify(penalidades).findByUsuarioId(ANA.getId());
        verifyNoMoreInteractions(viajes, solicitudes, penalidades);
    }

    private byte[] descargar(MockHttpServletRequestBuilder request) throws Exception {
        byte[] pdf = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"historial-viajes.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).hasSizeGreaterThan(5);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        return pdf;
    }

    private static String textoPdf(byte[] pdf) throws Exception {
        try (var documento = Loader.loadPDF(pdf)) {
            assertThat(documento.getNumberOfPages()).isPositive();
            return new PDFTextStripper().getText(documento);
        }
    }

    private void registrar(Usuario usuario) {
        when(usuarios.findByCorreoInstitucional(usuario.getCorreoInstitucional())).thenReturn(Optional.of(usuario));
    }

    private String bearer(Usuario usuario) {
        return "Bearer " + jwt.generarToken(usuario.getCorreoInstitucional());
    }

    private static MockHttpServletRequestBuilder pdf() {
        return get("/api/historial/pdf").contextPath("/api");
    }

    private static Usuario usuario(long id, String nombre, Rol rol) {
        return Usuario.builder().id(id).nombre(nombre).apellidos("Prueba").rolPrincipal(rol)
                .correoInstitucional("estudiante" + id + "@upc.edu.pe")
                .contrasenaHash("fixture").cuentaVerificada(true).build();
    }

    private static Viaje viaje(long id, Usuario conductor, String origen, LocalDate fecha, int hora, EstadoViaje estado) {
        Ruta ruta = Ruta.builder().conductor(conductor).origen(origen).destino("UPC").build();
        return Viaje.builder().id(id).ruta(ruta).fecha(fecha).hora(LocalTime.of(hora, 0))
                .dia("Sábado").estado(estado).build();
    }

    private static Solicitud solicitud(Viaje viaje) {
        return Solicitud.builder().viaje(viaje).estado(EstadoSolicitud.ACEPTADA).build();
    }

    @org.springframework.context.annotation.Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, UsuarioDetailsService.class,
            LimiteAutenticacion.class, HistorialController.class, GlobalExceptionHandler.class,
            MensajeService.class, EstadisticasService.class, UsuarioService.class})
    static class Configuration {
        @Bean UsuarioRepository usuarios() { return mock(UsuarioRepository.class); }
        @Bean ViajeRepository viajes() { return mock(ViajeRepository.class); }
        @Bean SolicitudRepository solicitudes() { return mock(SolicitudRepository.class); }
        @Bean PenalidadRepository penalidades() { return mock(PenalidadRepository.class); }
        @Bean UsuarioMapper usuarioMapper() { return mock(UsuarioMapper.class); }
        @Bean PerfilRolService perfilRolService() { return mock(PerfilRolService.class); }
        @Bean Clock clock() { return Clock.fixed(NOW, TiempoConfig.LIMA); }
    }
}
