package com.uniride.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.repositories.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class PerfilPersistenciaIntegrationTest {
    @Autowired WebApplicationContext contexto;
    @Autowired UsuarioRepository usuarios;
    @Test @WithMockUser(username="perfil-hardening@upc.edu.pe",roles="PASAJERO")
    void edicionPersisteIdentidadCoherenteEnAmbosEndpoints() throws Exception {
        usuarios.save(Usuario.builder().correoInstitucional("perfil-hardening@upc.edu.pe").telefono("+51912345678")
                .nombre("Anterior").apellidos("Anterior").contrasenaHash("hash").rolPrincipal(Rol.PASAJERO)
                .aceptaTerminos(true).cuentaVerificada(true).build());
        var mvc=MockMvcBuilders.webAppContextSetup(contexto).apply(springSecurity()).build();
        mvc.perform(put("/perfil").contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre":"Nueva", "apellidos":"Identidad", "carrera":"Software", "distrito":"Surco",
                 "universidad":"UPC", "diasDisponibles":"Lunes", "horariosDisponibles":"8:00",
                 "fechaInicioClases":"2026-03-01", "fechaFinClases":"2026-07-01"}
                """)).andExpect(status().isOk());
        for(String ruta:new String[]{"/perfil","/usuarios/me"}) mvc.perform(get(ruta))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Nueva"))
                .andExpect(jsonPath("$.apellidos").value("Identidad"));
    }
}
