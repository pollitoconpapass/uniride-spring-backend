package com.uniride.controllers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.uniride.entities.*;
import com.uniride.enums.Rol;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.*;
import com.uniride.services.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PerfilHardeningHttpTest {
    @Test void fechasInvertidasDan400YNombreSeSincroniza() throws Exception {
        var perfiles = mock(PerfilRepository.class);
        var usuarios = mock(UsuarioService.class);
        Usuario usuario = Usuario.builder().id(1L).nombre("Anterior").rolPrincipal(Rol.PASAJERO).build();
        Perfil perfil = Perfil.builder().usuario(usuario).build();
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(perfiles.findByUsuarioId(1L)).thenReturn(Optional.of(perfil));
        when(perfiles.save(any())).thenAnswer(i -> i.getArgument(0));
        var servicio = new PerfilService(perfiles, Mappers.getMapper(PerfilMapper.class), usuarios,
                mock(NotificacionService.class), mock(SolicitudRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(new PerfilController(servicio))
                .setControllerAdvice(new GlobalExceptionHandler(new MensajeService(new StaticMessageSource()))).build();
        String json = """
                {"nombre":"Ana", "apellidos":"Perez", "carrera":"Software", "distrito":"Surco",
                 "universidad":"UPC", "diasDisponibles":"Lunes", "horariosDisponibles":"8:00",
                 "fechaInicioClases":"2026-03-01", "fechaFinClases":"2026-07-01"}
                """;
        mvc.perform(put("/perfil").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("2026-07-01", "2026-02-28"))).andExpect(status().isBadRequest());
        verify(perfiles, never()).save(any());
        mvc.perform(put("/perfil").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Ana"));
        assertThat(usuario.getNombre()).isEqualTo("Ana");
        assertThat(usuario.getApellidos()).isEqualTo("Perez");
        mvc.perform(get("/perfil")).andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Ana"));
    }
}
