package com.uniride.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class BusquedaUs10RepositoryTest {
    @Autowired UsuarioRepository usuarios;
    @Autowired RutaRepository rutas;
    @Autowired ViajeRepository viajes;

    private Usuario crearConductor(String correo) {
        return usuarios.save(Usuario.builder().correoInstitucional(correo)
                .contrasenaHash("$2a$10$abcdefghijklmnopqrstuv")
                .nombre("Ana").apellidos("Pérez")
                .telefono("9" + String.format("%09d", Math.abs(correo.hashCode() % 1_000_000_000)))
                .rolPrincipal(Rol.CONDUCTOR).aceptaTerminos(true).build());
    }
    private Ruta ruta(Usuario usuario, String origen, EstadoRuta estado, int capacidad) {
        return rutas.save(Ruta.builder().conductor(usuario).origen(origen).destino("UPC")
                .puntosReferencia("Puente").capacidadMaxima(capacidad).estado(estado).build());
    }
    private Viaje viaje(Ruta ruta, int dias, int pasajeros, EstadoViaje estado) {
        LocalDate fecha=LocalDate.now(ZoneId.systemDefault()).plusDays(dias);
        return viajes.save(Viaje.builder().ruta(ruta).fecha(fecha).hora(LocalTime.of(9,0))
                .dia("Lunes").pasajeros(pasajeros).estado(estado).build());
    }
    private Page<Viaje> buscar(String origen, String distrito, int page, int size) {
        return viajes.buscarViajesRelevantes(origen,"upc",null,null,distrito,
                EstadoViaje.PROGRAMADO,EstadoRuta.ELIMINADA,
                LocalDate.now(ZoneId.systemDefault()),LocalTime.now(ZoneId.systemDefault()),
                PageRequest.of(page,size));
    }

    @Test
    void coincideParcialPeroPriorizaOrigenExactoAunqueSeaPosterior() {
        Usuario usuario=crearConductor("rankingus10@upc.edu.pe");
        Viaje parcial=viaje(ruta(usuario,"San Borja Sur",EstadoRuta.ACTIVA,3),1,0,EstadoViaje.PROGRAMADO);
        Viaje exacto=viaje(ruta(usuario,"San Borja",EstadoRuta.ACTIVA,3),2,0,EstadoViaje.PROGRAMADO);
        assertThat(buscar("san borja",null,0,10).getContent()).extracting(Viaje::getId)
                .containsExactly(exacto.getId(),parcial.getId());
    }

    @Test
    void ordenaAntesDePaginar() {
        Usuario usuario=crearConductor("rankingpaginaus10@upc.edu.pe");
        viaje(ruta(usuario,"San Borja Sur",EstadoRuta.ACTIVA,3),1,0,EstadoViaje.PROGRAMADO);
        Viaje exacto=viaje(ruta(usuario,"San Borja",EstadoRuta.ACTIVA,3),2,0,EstadoViaje.PROGRAMADO);
        assertThat(buscar("san borja",null,0,1).getContent()).extracting(Viaje::getId)
                .containsExactly(exacto.getId());
    }

    @Test
    void excluyeViajesLlenosEliminadosCanceladosYPasados() {
        Usuario usuario=crearConductor("exclusionus10@upc.edu.pe");
        Ruta activa=ruta(usuario,"Surco",EstadoRuta.ACTIVA,2);
        Viaje esperado=viaje(activa,1,1,EstadoViaje.PROGRAMADO);
        viaje(activa,2,2,EstadoViaje.PROGRAMADO);
        viaje(activa,3,0,EstadoViaje.CANCELADO);
        viaje(activa,-1,0,EstadoViaje.PROGRAMADO);
        viaje(ruta(usuario,"Surco",EstadoRuta.ELIMINADA,2),4,0,EstadoViaje.PROGRAMADO);
        assertThat(buscar("surco",null,0,10).getContent()).extracting(Viaje::getId)
                .containsExactly(esperado.getId());
    }

    @Test
    void cercaniaFiltraDistritoSinGeolocalizacion() {
        Usuario usuario=crearConductor("cercaniaus10@upc.edu.pe");
        viaje(ruta(usuario,"San Borja",EstadoRuta.ACTIVA,3),1,0,EstadoViaje.PROGRAMADO);
        viaje(ruta(usuario,"Surco",EstadoRuta.ACTIVA,3),1,0,EstadoViaje.PROGRAMADO);
        assertThat(buscar(null,"san borja",0,10).getContent()).hasSize(1);
    }

    @Test
    void sinCoincidenciasDevuelvePaginaVacia() {
        assertThat(buscar("no-existe",null,0,10).getContent()).isEmpty();
    }
}
