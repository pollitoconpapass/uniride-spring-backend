package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.confirmation.TipoOperacion;
import com.uniride.agent.dto.ChatRequest;
import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.ResolucionConfirmacion;
import com.uniride.config.TiempoConfig;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.repositories.*;
import com.uniride.security.JwtService;
import com.uniride.services.NotificacionService;
import com.uniride.services.PerfilRolService;
import com.uniride.services.ViajeService;
import jakarta.servlet.Filter;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"uniride.viajes.vencimiento-ms=3600000", "uniride.mail.reintento-ms=3600000"})
@Import(CoordinacionConductorIntegrationTest.ConfiguracionTiempo.class)
class CoordinacionConductorIntegrationTest {
    static final Instant INICIO = Instant.parse("2026-11-25T13:00:00Z");
    @Autowired AgentService agente;
    @Autowired ConfirmationService confirmaciones;
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;
    @Autowired SolicitudRepository solicitudes;
    @Autowired PerfilRepository perfiles;
    @Autowired PerfilRolService roles;
    @Autowired NotificacionRepository notificaciones;
    @Autowired CorreoPendienteRepository correos;
    @Autowired PenalidadRepository penalidades;
    @Autowired NotificacionService avisos;
    @Autowired ViajeService gestionViajes;
    @Autowired RelojPrueba reloj;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext web;
    @Autowired @Qualifier("springSecurityFilterChain") Filter seguridad;
    @MockitoBean ChatClient proveedor;
    Usuario conductor;
    Usuario ana;
    Usuario luis;

    @BeforeEach void prepararIdentidades() {
        reloj.instante.set(INICIO);
        conductor = usuario("María", Rol.CONDUCTOR);
        ana = usuario("Ana", Rol.PASAJERO);
        luis = usuario("Luis", Rol.PASAJERO);
        autenticar(conductor);
    }

    @AfterEach void limpiarIdentidad() {
        SecurityContextHolder.clearContext();
        verifyNoInteractions(proveedor);
    }

    @Test void listaYDetallePermitenElegirAlPasajeroYConfirmarLaAceptacion() throws Exception {
        var viaje = viaje(conductor, "Surco", 30, EstadoViaje.PROGRAMADO, false);
        var solicitudAna = solicitud(viaje, ana);
        var solicitudLuis = solicitud(viaje, luis);
        perfiles.save(Perfil.builder().usuario(ana).nombre("Ana").apellidos("Prueba").carrera("Derecho")
                .distrito("Surco").universidad("UPC").diasDisponibles("Lunes").horariosDisponibles("08:00")
                .fechaInicioClases(LocalDate.of(2026, 8, 1)).fechaFinClases(LocalDate.of(2026, 12, 20)).build());
        var ajeno = viaje(usuario("Otro conductor", Rol.CONDUCTOR), "Ate", 30, EstadoViaje.PROGRAMADO, false);
        solicitud(ajeno, usuario("Julia", Rol.PASAJERO));

        var mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(seguridad).build();
        var respuestaHttp = mvc.perform(post("/api/agent/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + jwt.generarToken(conductor.getCorreoInstitucional()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ChatRequest("¿Quién quiere unirse a mi viaje?", null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultados[0].datos.total").value(2))
                .andReturn().getResponse();
        var lista = mapper.readValue(respuestaHttp.getContentAsByteArray(), ChatResponse.class);
        assertThat(lista.message()).contains("Ana", "Luis").doesNotContain("Julia", ana.getCorreoInstitucional());

        autenticar(conductor);
        var pregunta = agente.chat("Muéstrame los datos del pasajero que solicitó cupo", lista.conversationId());
        assertThat(pregunta.operacion().estado()).isEqualTo(EstadoOperacion.AMBIGUO);
        assertThatThrownBy(() -> responderFormulario(pregunta, "solicitud", 909090L))
                .isInstanceOf(CamposInvalidosException.class);
        var detalle = responderFormulario(pregunta, "solicitud", solicitudAna.getId());
        assertThat(detalle.message()).contains("Ana", "Derecho", "¿Podemos encontrarnos en el parque?")
                .doesNotContain("Luis", ana.getCorreoInstitucional(), ana.getTelefono());

        var propuesta = agente.chat("Acepta la solicitud de ese pasajero", detalle.conversationId());
        assertThat(propuesta.operacion().confirmacion().tipo()).isEqualTo(TipoOperacion.ACEPTAR_SOLICITUD);
        assertThat(estado(solicitudAna)).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(notificaciones.findByUsuarioIdOrderByFechaEnvioDesc(ana.getId())).isEmpty();

        var confirmada = agente.chat("sí", propuesta.conversationId());
        assertThat(confirmada.resolucionConfirmacion().estado()).isEqualTo(ResolucionConfirmacion.Estado.EJECUTADA);
        var repeticion = confirmaciones.confirmar(propuesta.operacion().confirmacion().id());
        assertThat(repeticion.resultado()).isEqualTo(confirmada.operacion());
        assertThat(estado(solicitudAna)).isEqualTo(EstadoSolicitud.ACEPTADA);
        assertThat(estado(solicitudLuis)).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(viajes.findById(viaje.getId()).orElseThrow().getPasajeros()).isEqualTo(1);
        assertThat(notificaciones.findByUsuarioIdOrderByFechaEnvioDesc(ana.getId())).hasSize(1);
    }

    @Test void rechazaUnaSolicitudYExigeRevisarElLoteSiCambianLosPendientes() {
        var viaje = viaje(conductor, "Pueblo Libre", 35, EstadoViaje.PROGRAMADO, false);
        var solicitudAna = solicitud(viaje, ana);
        var solicitudLuis = solicitud(viaje, luis);
        var propuesta = pedir("Rechaza las solicitudes pendientes de este viaje");
        assertThat(propuesta.operacion().confirmacion().resumen()).contains("2 solicitudes pendientes");
        var nuevaSolicitud = solicitud(viaje, usuario("Julia", Rol.PASAJERO));

        assertThat(agente.chat("sí", propuesta.conversationId()).resolucionConfirmacion().estado())
                .isEqualTo(ResolucionConfirmacion.Estado.RECHAZADA);
        assertThat(estado(solicitudAna)).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(estado(solicitudLuis)).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(estado(nuevaSolicitud)).isEqualTo(EstadoSolicitud.PENDIENTE);

        var individual = pedir("Rechaza la solicitud de Ana, motivo: Cambio de punto de encuentro");
        confirmaciones.confirmar(individual.operacion().confirmacion().id());
        assertThat(solicitudes.findById(solicitudAna.getId()).orElseThrow().getMotivoRechazo())
                .isEqualTo("Cambio de punto de encuentro");
        var lote = pedir("Rechaza las solicitudes pendientes del viaje #" + viaje.getId());
        assertThat(agente.chat("sí", lote.conversationId()).message()).contains("2");
        assertThat(estado(solicitudLuis)).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(estado(nuevaSolicitud)).isEqualTo(EstadoSolicitud.RECHAZADA);
    }

    @Test void rechazaLasSolicitudesElegidasYConservaLasDemas() {
        var viaje = viaje(conductor, "Surco", 35, EstadoViaje.PROGRAMADO, false);
        var primera = solicitud(viaje, ana);
        var segunda = solicitud(viaje, luis);
        var propuesta = pedir("Rechaza las solicitudes #" + primera.getId() + " y #" + segunda.getId());
        assertThat(propuesta.operacion().confirmacion().tipo()).isEqualTo(TipoOperacion.RECHAZAR_SOLICITUDES_PENDIENTES);
        var otra = solicitud(viaje, usuario("Julia", Rol.PASAJERO));
        agente.chat("sí", propuesta.conversationId());
        assertThat(estado(primera)).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(estado(segunda)).isEqualTo(EstadoSolicitud.RECHAZADA);
        assertThat(estado(otra)).isEqualTo(EstadoSolicitud.PENDIENTE);
    }

    @Test void dosConfirmacionesConcurrentesDevuelvenElMismoRecibo() throws Exception {
        var viaje = viaje(conductor, "Surco", 35, EstadoViaje.PROGRAMADO, false);
        var solicitud = solicitud(viaje, ana);
        var id = pedir("Acepta la solicitud #" + solicitud.getId()).operacion().confirmacion().id();
        try (var ejecutor = Executors.newFixedThreadPool(2)) {
            var primera = ejecutor.submit(() -> confirmarComoConductor(id));
            var segunda = ejecutor.submit(() -> confirmarComoConductor(id));
            assertThat(primera.get(10, TimeUnit.SECONDS)).isEqualTo(segunda.get(10, TimeUnit.SECONDS));
        }
        assertThat(estado(solicitud)).isEqualTo(EstadoSolicitud.ACEPTADA);
        assertThat(viajes.findById(viaje.getId()).orElseThrow().getPasajeros()).isEqualTo(1);
        assertThat(notificaciones.findByUsuarioIdOrderByFechaEnvioDesc(ana.getId())).hasSize(1);
    }

    @Test void confirmaIniciaYCompletaConLasReglasDeHorarioYEstado() {
        var viaje = viaje(conductor, "Lince", 30, EstadoViaje.PROGRAMADO, false);
        var confirmar = pedir("Confirma mi viaje de mañana");
        assertThat(confirmar.operacion().confirmacion().tipo()).isEqualTo(TipoOperacion.CONFIRMAR_VIAJE);
        assertThat(viajes.findById(viaje.getId()).orElseThrow().isConfirmado()).isFalse();
        agente.chat("sí", confirmar.conversationId());
        assertThat(viajes.findById(viaje.getId()).orElseThrow().isConfirmado()).isTrue();
        assertThat(pedir("Ya estamos saliendo, inicia el viaje").message()).contains("antes de su hora programada");
        reloj.instante.set(INICIO.plus(Duration.ofHours(30)));
        var iniciar = pedir("Ya estamos saliendo, inicia el viaje");
        assertThat(agente.chat("sí", iniciar.conversationId()).message()).isEqualTo("Viaje iniciado");
        assertThat(viajes.findById(viaje.getId()).orElseThrow().getEstado()).isEqualTo(EstadoViaje.EN_PROGRESO);
        var completar = pedir("Terminamos, marca el viaje como completado");
        assertThat(agente.chat("sí", completar.conversationId()).message()).isEqualTo("Viaje completado");
        assertThat(viajes.findById(viaje.getId()).orElseThrow().getEstado()).isEqualTo(EstadoViaje.COMPLETADO);
    }

    @Test void cancelacionPresentaElImpactoYConsultaElEstadoRealDeLosAvisos() {
        var viaje = viaje(conductor, "Miraflores", 50, EstadoViaje.PROGRAMADO, true);
        var afectada = solicitud(viaje, ana);
        var propuesta = pedir("Cancela el viaje del viernes");
        assertThat(propuesta.operacion().confirmacion().resumen())
                .contains("No se aplicará una penalidad", "CANCELADA: 1", "aviso: 1");
        assertThat(avisos.estadoAvisosCancelacion(viaje.getId(), conductor.getId())).isEmpty();
        assertThat(agente.chat("sí", propuesta.conversationId()).message()).contains("Viaje cancelado sin penalidad");
        assertThat(estado(afectada)).isEqualTo(EstadoSolicitud.CANCELADA);
        var avisoPasajero = notificaciones.findByUsuarioIdOrderByFechaEnvioDesc(ana.getId()).getFirst();
        var correoPendiente = correos.findAll().stream()
                .filter(correo -> correo.getNotificacionId().equals(avisoPasajero.getId())).findFirst().orElseThrow();
        correoPendiente.setIntentos(1);
        correos.save(correoPendiente);
        var estadoAvisos = agente.chat("¿Qué avisos se generaron por la cancelación?", propuesta.conversationId());
        assertThat(estadoAvisos.message()).contains("Avisos para ti", "Avisos para pasajeros", "en reintento 1")
                .doesNotContain(ana.getCorreoInstitucional());
        correoPendiente.setEnviado(reloj.instant());
        correos.save(correoPendiente);
        assertThat(agente.chat("¿Qué avisos se generaron por la cancelación?", propuesta.conversationId()).message())
                .contains("Correos enviados 1");
        assertThat(avisos.estadoAvisosCancelacion(viaje.getId(), luis.getId())).isEmpty();
    }

    @Test void exigeNuevaConfirmacionSiCambiaLaPenalidadAntesDeLaCancelacion() {
        var viaje = viaje(conductor, "San Borja", 24, EstadoViaje.PROGRAMADO, true);
        viaje.setHora(viaje.getHora().plusMinutes(4));
        viajes.save(viaje);
        var propuesta = pedir("Cancela el viaje");
        assertThat(propuesta.message()).contains("No se aplicará una penalidad");
        reloj.instante.set(INICIO.plus(Duration.ofMinutes(5)));
        var vencida = agente.chat("sí", propuesta.conversationId());
        assertThat(vencida.message()).contains("Cambió la penalidad");
        assertThat(viajes.findById(viaje.getId()).orElseThrow().getEstado()).isEqualTo(EstadoViaje.PROGRAMADO);
        assertThat(penalidades.existsByViajeId(viaje.getId())).isFalse();

        var nueva = pedir("Cancela el viaje");
        assertThat(nueva.message()).contains("penalidad LEVE");
        agente.chat("no", nueva.conversationId());
        assertThat(confirmaciones.obtener(nueva.operacion().confirmacion().id()).estado())
                .isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThat(penalidades.existsByViajeId(viaje.getId())).isFalse();
    }

    @Test void protegeElViajeYSusSolicitudesYNoAtribuyeAvisosHistoricos() {
        var viaje = viaje(conductor, "Barranco", 30, EstadoViaje.PROGRAMADO, false);
        var solicitud = solicitud(viaje, ana);
        var propuesta = pedir("Acepta la solicitud #" + solicitud.getId());
        autenticar(usuario("Otro conductor", Rol.CONDUCTOR));
        assertThat(pedir("Muéstrame la solicitud #" + solicitud.getId()).operacion().estado())
                .isEqualTo(EstadoOperacion.NO_ENCONTRADO);
        assertThatThrownBy(() -> confirmaciones.confirmar(propuesta.operacion().confirmacion().id()))
                .isInstanceOf(ResourceNotFoundException.class);
        autenticar(ana);
        assertThatThrownBy(() -> pedir("Cancela el viaje #" + viaje.getId())).isInstanceOf(NoPermitidoException.class);

        autenticar(conductor);
        var historico = viaje(conductor, "Centro", -72, EstadoViaje.CANCELADO, true);
        avisos.notificar(conductor, TipoNotificacion.EXITO, "Viaje cancelado", "Aviso antiguo sin vínculo.");
        var consulta = pedir("Consulta los avisos de cancelación del viaje #" + historico.getId());
        assertThat(consulta.message()).contains("no vincula avisos").doesNotContain("Aviso antiguo");
    }

    @Test void solicitaElViajeCorrectoCuandoHayDosSalidasYNoPreparaParaUnaNegacion() {
        var a = viaje(conductor, "Surco", 30, EstadoViaje.PROGRAMADO, false);
        var b = viaje(conductor, "Ate", 31, EstadoViaje.PROGRAMADO, false);
        var pregunta = pedir("Confirma mi viaje de mañana");
        assertThat(pregunta.aclaracion().campos().getFirst().opciones()).hasSize(2);
        assertThat(TextoHablado.desde(pregunta)).contains("Opción 1.", "Opción 2.");
        var propuesta = agente.chat("el segundo", pregunta.conversationId());
        assertThat(propuesta.message()).contains("Ate").doesNotContain("Surco");
        agente.chat("sí", propuesta.conversationId());
        assertThat(viajes.findById(a.getId()).orElseThrow().isConfirmado()).isFalse();
        assertThat(viajes.findById(b.getId()).orElseThrow().isConfirmado()).isTrue();
        assertThat(pedir("Cancela el viaje de mañana y confirma el del viernes").operacion()).isNull();
        assertThat(CoordinacionConductorIntent.resolver("¿Cómo cancelo el viaje de mañana?", null, null,
                null, Rol.CONDUCTOR, reloj)).isNull();
    }

    private ChatResponse responderFormulario(ChatResponse pregunta, String campo, Long id) {
        return agente.chat("Datos de aclaración " + pregunta.aclaracion().id() + ": {\"" + campo + "\":\"" + id + "\"}",
                pregunta.conversationId());
    }

    private ChatResponse pedir(String mensaje) { return agente.chat(mensaje, (UUID) null); }

    private com.uniride.agent.dto.ConfirmacionResumen confirmarComoConductor(UUID id) {
        autenticar(conductor);
        try {
            return confirmaciones.confirmar(id);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private EstadoSolicitud estado(Solicitud solicitud) {
        return solicitudes.findById(solicitud.getId()).orElseThrow().getEstado();
    }

    private Usuario usuario(String nombre, Rol rol) {
        var usuario = usuarios.save(Usuario.builder().nombre(nombre).apellidos("Prueba")
                .correoInstitucional(UUID.randomUUID() + "@upc.edu.pe").telefono("test-" + UUID.randomUUID())
                .contrasenaHash("test").aceptaTerminos(true).cuentaVerificada(true).rolPrincipal(rol).build());
        roles.asegurarPerfil(usuario, rol);
        return usuario;
    }

    private void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                usuario.getCorreoInstitucional(), null, List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getRolPrincipal()))));
    }

    private Viaje viaje(Usuario propietario, String origen, int horas, EstadoViaje estado, boolean confirmado) {
        Ruta ruta = rutas.save(Ruta.builder().conductor(propietario).origen(origen).destino("UPC")
                .capacidadMaxima(4).puntosReferencia("Parque").estado(EstadoRuta.ACTIVA).build());
        LocalDateTime salida = LocalDateTime.now(reloj).plusHours(horas);
        return viajes.save(Viaje.builder().ruta(ruta).fecha(salida.toLocalDate()).hora(salida.toLocalTime())
                .dia("Jueves").estado(estado).confirmado(confirmado).build());
    }

    private Solicitud solicitud(Viaje viaje, Usuario pasajero) {
        return solicitudes.save(Solicitud.builder().viaje(viaje).pasajero(pasajero).estado(EstadoSolicitud.PENDIENTE)
                .preferenciaCompensacion(TipoCompensacion.GASOLINA).mensaje("¿Podemos encontrarnos en el parque?").build());
    }

    @TestConfiguration
    static class ConfiguracionTiempo {
        @Bean @Primary RelojPrueba relojCoordinacion() { return new RelojPrueba(); }
    }

    static final class RelojPrueba extends Clock {
        final AtomicReference<Instant> instante = new AtomicReference<>(INICIO);
        @Override public ZoneId getZone() { return TiempoConfig.LIMA; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instante.get(); }
    }
}
