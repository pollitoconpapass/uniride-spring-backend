package com.uniride.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniride.controllers.BusquedaController;
import com.uniride.controllers.RutaController;
import com.uniride.controllers.SolicitudController;
import com.uniride.controllers.ViajeController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
class AutorizacionRolesTest {

    @Autowired
    private RutaController rutaController;

    @Autowired
    private BusquedaController busquedaController;

    @Autowired
    private ViajeController viajeController;

    @Autowired
    private SolicitudController solicitudController;

    @Test
    @WithMockUser(
            username = "pasajero@upc.edu.pe",
            roles = "PASAJERO")
    void pasajeroNoPuedeAccederModuloDeRutasDelConductor() {

        assertThatThrownBy(() ->
                rutaController.listarMisRutas(null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(
            username = "conductor@upc.edu.pe",
            roles = "CONDUCTOR")
    void conductorNoPuedeBuscarViajesComoPasajero() {

        assertThatThrownBy(() ->
                busquedaController.buscar(
                        null,
                        null,
                        null,
                        null,
                        false,
                        0,
                        10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(
            username = "pasajero@upc.edu.pe",
            roles = "PASAJERO")
    void pasajeroNoPuedeConfirmarViajeComoConductor() {

        assertThatThrownBy(() ->
                viajeController.confirmarViaje(1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(
            username = "conductor@upc.edu.pe",
            roles = "CONDUCTOR")
    void conductorNoPuedeCrearSolicitudComoPasajero() {

        assertThatThrownBy(() ->
                solicitudController.solicitar(1L, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}