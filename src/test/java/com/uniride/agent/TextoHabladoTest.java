package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.MetodoCompensacionResumen;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.ResultadoAgente;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.agent.tools.ContribucionAgentTools;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TextoHabladoTest {

    @Test
    void keepsTheMessageAndStripsMarkdown() {
        String texto = TextoHablado.desde(respuesta("**Hola** • revisa tu _ruta_.", List.of(), List.of(), List.of()));
        assertThat(texto).isEqualTo("Hola revisa tu ruta.");
    }

    @Test
    void enumeratesRoutesWithTheirState() {
        var rutas = List.of(
                new RutaResumen(1L, "Surco", "UPC", null, null, 4, EstadoRuta.ACTIVA),
                new RutaResumen(2L, "San Miguel", "UPC", null, null, 4, EstadoRuta.LLENA));
        String texto = TextoHablado.desde(respuesta("Tienes dos rutas.", List.of(), rutas, List.of()));

        assertThat(texto).contains("Tus rutas son: Surco a UPC; San Miguel a UPC, llena.");
    }

    @Test
    void readsRegisteredRouteLabelsWithoutTheirOpaqueValues() {
        var opciones = IntStream.rangeClosed(1, 7).mapToObj(i -> new OpcionAclaracion("valor-opaco-" + i,
                i + ". Surco → UPC · Capacidad 4 · Referencia " + i)).toList();
        var esquema = Aclaraciones.crear("crearViaje", Aclaraciones.Campo.RUTA.conOpciones(opciones));
        var respuesta = respuesta("Selecciona una de tus rutas.", List.of(), List.of(), List.of()).conAclaracion(esquema);

        assertThat(TextoHablado.desde(respuesta)).contains("Ruta 1. Surco a UPC, Capacidad 4, Referencia 1",
                "Referencia 5", "Hay más opciones en pantalla.", "Puedes indicar el número de la ruta.")
                .doesNotContain("valor-opaco", "Referencia 6");
    }

    @Test
    void readsTripsWithDateAndTime() {
        var viaje = new ViajeResumen(5L, 2L, "Surco", "UPC", "Lunes", LocalDate.of(2026, 11, 30),
                LocalTime.of(7, 15), null, "Ana", 4, EstadoViaje.PROGRAMADO, null);
        String texto = TextoHablado.desde(respuesta("Encontré un viaje.", List.of(viaje), List.of(), List.of()));

        assertThat(texto).contains("Tu viaje es: Surco a UPC, el 30 de noviembre a las 7:15, con Ana.");
    }

    @Test
    void readsCompensationMethods() {
        var metodos = List.of(
                new MetodoCompensacionResumen(1L, TipoCompensacion.DINERO, "Yape", true),
                new MetodoCompensacionResumen(2L, TipoCompensacion.FAVOR, null, false));
        String texto = TextoHablado.desde(respuesta("Tienes dos métodos.", List.of(), List.of(), metodos));

        assertThat(texto).contains("DINERO, Yape, activo; FAVOR, inactivo");
    }

    @Test
    void capsLongListsAndPointsToTheScreen() {
        var rutas = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> new RutaResumen((long) i, "Origen " + i, "Destino " + i, null, null, 4, EstadoRuta.ACTIVA))
                .toList();
        String texto = TextoHablado.desde(respuesta("Tienes siete rutas.", List.of(), rutas, List.of()));

        assertThat(texto).contains("Hay 2 más en pantalla.");
        assertThat(texto).doesNotContain("Origen 6");
    }

    @Test
    void announcesMorePages() {
        var datos = new ObjectMapper().valueToTree(Map.of("hayMas", true));
        var respuesta = new ChatResponse("Revisa los resultados.", List.of(), List.of(), List.of(),
                null, List.of(new ResultadoAgente("buscarViajes", datos)), null, null);
        assertThat(TextoHablado.desde(respuesta)).endsWith("Hay más resultados en pantalla.");
    }

    @Test
    void speaksVerifiedContributionAmountsWithSpanishDecimals() {
        var datos = new ObjectMapper().valueToTree(Map.of("estado", "SUGERIDA", "sugerencia",
                Map.of("pasajeros", 3, "aporteEstimado", 2.67, "litrosGasolinaAprox", 0.49)));
        var respuesta = new ChatResponse("Detalle en pantalla.", List.of(), List.of(), List.of(), null,
                List.of(new ResultadoAgente(ContribucionAgentTools.NOMBRE, datos)));

        assertThat(TextoHablado.desde(respuesta)).isEqualTo("Para 3 pasajeros, el aporte estimado por pasajero "
                + "es de 2,67 soles o 0,49 litros de gasolina. También puedes ofrecer un favor equivalente. "
                + "El aporte final depende del acuerdo con el conductor.");
    }

    @Test
    void keepsTheContributionClarificationWhenThereIsNoEstimate() {
        var datos = new ObjectMapper().valueToTree(Map.of("estado", "FALTAN_PASAJEROS"));
        var respuesta = new ChatResponse("¿Cuántos pasajeros harán el viaje?", List.of(), List.of(), List.of(),
                null, List.of(new ResultadoAgente(ContribucionAgentTools.NOMBRE, datos)));

        assertThat(TextoHablado.desde(respuesta)).isEqualTo("¿Cuántos pasajeros harán el viaje?");
    }

    @Test
    void truncatesAtASentenceBoundary() {
        String texto = TextoHablado.desde(respuesta("Frase corta. ".repeat(100), List.of(), List.of(), List.of()));
        assertThat(texto.length()).isLessThanOrEqualTo(900);
        assertThat(texto).endsWith(".");
    }

    private static ChatResponse respuesta(String message, List<ViajeResumen> viajes,
                                          List<RutaResumen> rutas, List<MetodoCompensacionResumen> metodos) {
        return new ChatResponse(message, viajes, rutas, metodos, null);
    }
}
