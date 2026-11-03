package com.uniride.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.services.MensajeService;
import com.uniride.services.NotificacionService;
import com.uniride.services.PerfilService;
import com.uniride.services.UsuarioService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Regresión HTTP de US05 con validación y persistencia simulada. */
class PerfilControllerHttpTest {

    private MockMvc mvc;
    private PerfilRepository repository;
    private Perfil perfil;

    @BeforeEach
    void preparar() {
        repository = mock(PerfilRepository.class);
        PerfilMapper mapper = mock(PerfilMapper.class);
        UsuarioService usuarioService = mock(UsuarioService.class);
        NotificacionService notificacionService = mock(NotificacionService.class);
        Usuario usuario = Usuario.builder().id(7L).build();
        perfil = Perfil.builder().id(3L).usuario(usuario).build();
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(repository.findByUsuarioId(7L)).thenReturn(Optional.of(perfil));
        when(repository.save(any(Perfil.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toPerfilRespuesta(any(Perfil.class))).thenAnswer(inv -> {
            Perfil p = inv.getArgument(0);
            return new PerfilRespuesta(p.getId(), p.getNombre(), p.getApellidos(), p.getCarrera(),
                    p.getDistrito(), p.getUniversidad(), p.getDiasDisponibles(),
                    p.getHorariosDisponibles(), p.getFechaInicioClases(), p.getFechaFinClases(),
                    p.getMetodoCompensacionFavorito(), p.getGustos(), p.getHobbies(),
                    p.getDatosCuriosos(), p.getFechaActualizacion());
        });

        SolicitudRepository solicitudRepository = mock(SolicitudRepository.class);
        PerfilService service = new PerfilService(repository, mapper, usuarioService,
                notificacionService, solicitudRepository);
        mvc = MockMvcBuilders.standaloneSetup(new PerfilController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new MensajeService(new StaticMessageSource())))
                .build();
    }

    private void enviarError(String body, String detalle) throws Exception {
        mvc.perform(put("/perfil/info-adicional")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString(detalle)));
        verify(repository, never()).save(any());
    }

    @Test
    void us05PrimeraCargaCorrectaEs200() throws Exception {
        mvc.perform(put("/perfil/info-adicional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gustos":"Música","hobbies":"Ciclismo","datosCuriosos":"Soy zurdo"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gustos").value("Música"))
                .andExpect(jsonPath("$.hobbies").value("Ciclismo"))
                .andExpect(jsonPath("$.datosCuriosos").value("Soy zurdo"));
    }

    @Test
    void us05FaltanDatosObligatoriosEs400() throws Exception {
        enviarError("""
                {"gustos":"Música","datosCuriosos":"Soy zurdo"}
                """, "hobbies");
    }

    @Test
    void us05PrimeraCargaVaciaEs400EIndicaRequeridos() throws Exception {
        enviarError("{}", "gustos, hobbies, datosCuriosos");
    }

    @Test
    void us05NoPermiteCadenasEnBlanco() throws Exception {
        enviarError("""
                {"gustos":" ","hobbies":"Ciclismo","datosCuriosos":"Soy zurdo"}
                """, "gustos");
    }

    @Test
    void us05ActualizacionParcialEs200YNoBorraCampos() throws Exception {
        perfil.setGustos("Música");
        perfil.setHobbies("Natación");
        perfil.setDatosCuriosos("Soy zurdo");
        mvc.perform(put("/perfil/info-adicional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hobbies":"Fotografía"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gustos").value("Música"))
                .andExpect(jsonPath("$.hobbies").value("Fotografía"))
                .andExpect(jsonPath("$.datosCuriosos").value("Soy zurdo"));
    }

    @Test
    void us05ActualizacionVaciaConPerfilCompletoEs400() throws Exception {
        perfil.setGustos("Música");
        perfil.setHobbies("Natación");
        perfil.setDatosCuriosos("Soy zurdo");
        enviarError("{}", "Debes indicar al menos un campo");
    }

    @Test
    void us05SinPerfilEs404() throws Exception {
        when(repository.findByUsuarioId(7L)).thenReturn(Optional.empty());
        mvc.perform(put("/perfil/info-adicional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gustos":"Música","hobbies":"Ciclismo","datosCuriosos":"Soy zurdo"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Primero completa tu perfil personal"));
    }

    @Test
    void us05JsonInvalidoEs400() throws Exception {
        mvc.perform(put("/perfil/info-adicional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }
}
