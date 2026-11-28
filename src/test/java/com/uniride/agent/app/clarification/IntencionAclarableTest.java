package com.uniride.agent.app.clarification;

import static org.assertj.core.api.Assertions.assertThat;
import com.uniride.agent.app.clarification.Aclaraciones.Campo;
import com.uniride.enums.Rol;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntencionAclarableTest {
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima"));

    @Test void aceptaUnaOrdenDeViajeAunqueLlegueConPreludioYComoPreguntaCortes() {
        var borrador = IntencionAclarable.desdeTool("crearViaje", Map.of(),
                "Ok, ¿puedes crear un viaje con la ruta de San Borja para mañana a las 6 de la tarde?",
                Rol.CONDUCTOR, RELOJ);
        assertThat(borrador).isNotNull();
        assertThat(borrador.argumentos()).containsEntry("origenRuta", "San Borja")
                .containsEntry("fecha", "2026-11-22").containsEntry("hora", "18:00");
    }

    @Test void separaElHorarioDelOrigenCuandoSeDiceParaLasSeis() {
        var borrador = IntencionAclarable.inicioLocal(
                "Quiero crear un viaje para mañana con la ruta de San Borja para las seis de la tarde.", null, RELOJ);
        assertThat(borrador).isNotNull();
        assertThat(borrador.argumentos()).containsEntry("origenRuta", "San Borja")
                .containsEntry("fecha", "2026-11-22").containsEntry("hora", "18:00");
    }

    @Test void reconoceCreameUnViajeYDevuelveElBorradorDelConductor() {
        var borrador = IntencionAclarable.siFaltanDatos("créame un viaje", Rol.CONDUCTOR, RELOJ);
        assertThat(borrador).isNotNull();
        assertThat(borrador.accion().herramienta).isEqualTo("crearViaje");
    }

    @Test void aceptaJergaCuandoElModeloYaEligioLaOperacion() {
        var borrador = IntencionAclarable.desdeTool("crearViaje", Map.of(),
                "me apuntas un viaje para mañana a las seis de la tarde", Rol.CONDUCTOR, RELOJ);
        assertThat(borrador).isNotNull();
        assertThat(borrador.argumentos()).containsEntry("fecha", "2026-11-22").containsEntry("hora", "18:00");
    }

    @Test void conservaSoloLosArgumentosDelModeloQueAparecenEnElMensaje() {
        var borrador = IntencionAclarable.desdeTool("crearViaje",
                Map.of("origenRuta", "San Borja", "destinoRuta", "Miraflores"),
                "me apuntas un viaje para mañana con salida en San Borja", Rol.CONDUCTOR, RELOJ);
        assertThat(borrador).isNotNull();
        assertThat(borrador.argumentos()).containsEntry("origenRuta", "San Borja")
                .containsEntry("fecha", "2026-11-22").doesNotContainKey("destinoRuta");
    }

    @Test void laJergaNoEvitaUnRechazoExplicito() {
        assertThat(IntencionAclarable.desdeTool("crearViaje", Map.of(),
                "mejor no, no quiero crear un viaje", Rol.CONDUCTOR, RELOJ)).isNull();
    }

    @Test void unaPreguntaInformativaNoSeConvierteEnOperacion() {
        assertThat(IntencionAclarable.desdeTool("eliminarRuta", Map.of("origenRuta", "San Borja"),
                "¿cómo elimino la ruta de San Borja?", Rol.CONDUCTOR, RELOJ)).isNull();
        assertThat(IntencionAclarable.desdeTool("eliminarRuta", Map.of("origenRuta", "San Borja"),
                "muéstrame la ruta de San Borja", Rol.CONDUCTOR, RELOJ)).isNull();
    }

    @Test void reconoceElCambioDeTemaCuandoSeActualizaElHorario() {
        assertThat(IntencionAclarable.cambiaDeTema(
                "Vale, y ahora puedes actualizar mi horario y poner que tengo cálculo 3 los viernes a las 7 de la mañana."))
                .isTrue();
    }

    @Test void corrigeLaHoraDelViajeAnteriorCuandoSeRefiereAEl() {
        var anterior = new IntencionAclarable.Contexto(IntencionAclarable.Accion.CREAR_VIAJE,
                "San Borja", "Miraflores", "2026-11-22", "18:00");
        var correccion = IntencionAclarable.inicioLocal(
                "Vale, me equivoqué. A ese viaje lo puedes poner a las 7, por favor.", anterior, RELOJ);
        assertThat(correccion).isNotNull();
        assertThat(correccion.accion().herramienta).isEqualTo("reprogramarViaje");
        assertThat(correccion.argumentos()).containsEntry("origen", "San Borja")
                .containsEntry("destino", "Miraflores").containsEntry("fecha", "2026-11-22")
                .containsEntry("hora", "18:00").containsEntry("nuevaFecha", "2026-11-22")
                .containsEntry("nuevaHora", "19:00");
    }

    @Test void respetaElPeriodoExplicitoAlCorregirLaHora() {
        var anterior = new IntencionAclarable.Contexto(IntencionAclarable.Accion.CREAR_VIAJE,
                "San Borja", null, "2026-11-22", "18:00");
        var correccion = IntencionAclarable.inicioLocal("A ese viaje ponlo a las 7 de la mañana", anterior, RELOJ);
        assertThat(correccion).isNotNull();
        assertThat(correccion.argumentos()).containsEntry("nuevaHora", "07:00");
    }

    @Test void unaFraseCortadaPorUnPuntoNoSeTomaComoDosOperaciones() {
        assertThat(IntencionAclarable.desdeTool("reprogramarViaje", Map.of(),
                "Vale, me equivoqué. A ese viaje lo puedes poner a las 7.", Rol.CONDUCTOR, RELOJ)).isNotNull();
    }

    @Test void nuevaRutaSoloReutilizaElOrigenCuandoSePide() {
        var previa = IntencionAclarable.inicioLocal("Crea una ruta con origen Surco, referencias Parque, capacidad 3", null, RELOJ);
        var nueva = IntencionAclarable.inicioLocal("Quiero crear otra", previa.contexto(), RELOJ);
        assertThat(nueva.argumentos()).isEmpty();
        var esquema = Aclaraciones.crear("crearRuta", Campo.ORIGEN.obligatorio(), Campo.REFERENCIAS.obligatorio(),
                Campo.CAPACIDAD.obligatorio());
        var aclarada = IntencionAclarable.continuar("Usa el mismo origen", nueva, esquema, previa.contexto(), RELOJ);
        assertThat(aclarada.argumentos()).containsOnlyKeys("origen").containsEntry("origen", "Surco");
    }

    @Test void conservaLaFechaDeUnViajeExplicitoParaCambiarSoloLaHora() {
        var previo = IntencionAclarable.inicioLocal("Crea un viaje con origen Surco, fecha 2026-12-01, hora ocho de la noche", null, RELOJ);
        var siguiente = IntencionAclarable.inicioLocal("Quiero cambiar la hora", previo.contexto(), RELOJ);
        assertThat(siguiente.argumentos()).containsEntry("fecha", "2026-12-01").containsEntry("hora", "20:00")
                .containsEntry("nuevaFecha", "2026-12-01").doesNotContainKey("nuevaHora");
    }
}
