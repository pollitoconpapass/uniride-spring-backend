package com.uniride.agent;

import static com.uniride.agent.dto.TipoCampoAclaracion.*;

import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.TipoCampoAclaracion;
import java.util.Arrays;
import java.util.List;

/** All form fields and contexts are server-owned. Tools select just the unmet requirements. */
public final class Aclaraciones {
    private static final List<OpcionAclaracion> COMPENSACIONES = List.of(
            new OpcionAclaracion("DINERO", "Dinero"), new OpcionAclaracion("GASOLINA", "Gasolina"),
            new OpcionAclaracion("FAVOR", "Favor"));

    private Aclaraciones() { }

    public static AclaracionAgente crear(String operacion, CampoAclaracion... campos) {
        return new AclaracionAgente(1, null, operacion, contexto(operacion), List.of(campos), null, null, "campo");
    }

    public static AclaracionAgente alternativa(String operacion, Campo... campos) {
        return new AclaracionAgente(1, null, operacion, contexto(operacion),
                Arrays.stream(campos).map(c -> c.opcional("Completa al menos uno de estos campos.")).toList(),
                Arrays.stream(campos).map(Campo::id).toList(), null, "campo");
    }

    private static String contexto(String operacion) {
        return switch (operacion) {
            case "crearRuta" -> "Crear una ruta";
            case "editarRuta" -> "Editar una ruta";
            case "eliminarRuta" -> "Identificar la ruta que quieres eliminar";
            case "crearViaje" -> "Crear un viaje";
            case "reprogramarViaje" -> "Reprogramar un viaje";
            case "solicitarUnirse" -> "Solicitar un cupo";
            case "cancelarSolicitud" -> "Identificar la solicitud que quieres cancelar";
            case "registrarMetodoCompensacion" -> "Registrar un método de compensación";
            case "actualizarMetodoCompensacion" -> "Editar un método de compensación";
            case "cambiarEstadoMetodoCompensacion" -> "Cambiar el estado de un método de compensación";
            case "eliminarMetodoCompensacion" -> "Identificar el método que quieres eliminar";
            case "sugerirContribucionViaje" -> "Consultar una sugerencia de contribución";
            case "prepararDescargaHistorialPdf" -> "Descargar el historial en PDF";
            default -> throw new IllegalArgumentException("Operación de aclaración inválida");
        };
    }

    public enum Campo {
        ORIGEN("origen", "Origen", TEXT, 255), DESTINO("destino", "Destino", TEXT, 255),
        RUTA("ruta", "Ruta registrada", SELECT, null),
        ORIGEN_ACTUAL("origenActual", "Origen actual", TEXT, 255),
        DESTINO_ACTUAL("destinoActual", "Destino actual", TEXT, 255),
        NUEVO_ORIGEN("nuevoOrigen", "Nuevo origen", TEXT, 255),
        REFERENCIAS("referencias", "Puntos de referencia", TEXTAREA, null),
        CAPACIDAD("capacidad", "Capacidad máxima", NUMBER, null),
        ZONAS_SIN_PARADA("zonasSinParada", "Zonas sin parada", TEXTAREA, null),
        NOTA_CONTRIBUCION("notaContribucion", "Nota de contribución", TEXTAREA, 255),
        DIA("dia", "Día de la semana", TEXT, 20), FECHA("fecha", "Fecha", DATE, null),
        HORA("hora", "Hora", TIME, null), NUEVA_FECHA("nuevaFecha", "Nueva fecha", DATE, null),
        NUEVA_HORA("nuevaHora", "Nueva hora", TIME, null),
        COMPENSACION("compensacion", "Tipo de compensación", SELECT, null),
        DESCRIPCION("descripcion", "Descripción", TEXTAREA, 255),
        TIPO_ACTUAL("tipoActual", "Tipo actual", SELECT, null),
        DESCRIPCION_ACTUAL("descripcionActual", "Descripción actual", TEXT, 255),
        NUEVO_TIPO("nuevoTipo", "Nuevo tipo", SELECT, null),
        NUEVA_DESCRIPCION("nuevaDescripcion", "Nueva descripción", TEXTAREA, 255),
        ESTADO("activo", "Acción", SELECT, null), PASAJEROS("pasajeros", "Cantidad de pasajeros", NUMBER, null),
        CONDUCTOR("conductor", "Nombre del conductor", TEXT, 120),
        PDF_DESDE("desde", "Fecha inicial", DATE, null), PDF_HASTA("hasta", "Fecha final", DATE, null),
        PDF_TIPO("tipo", "Rol del historial", SELECT, null), PDF_PERIODO("periodo", "Período", TEXT, 120),
        PDF_ANIO("anio", "Año", NUMBER, null);

        private final String id;
        private final String etiqueta;
        private final TipoCampoAclaracion tipo;
        private final Integer longitudMaxima;

        Campo(String id, String etiqueta, TipoCampoAclaracion tipo, Integer longitudMaxima) {
            this.id = id;
            this.etiqueta = etiqueta;
            this.tipo = tipo;
            this.longitudMaxima = longitudMaxima;
        }

        public String id() { return id; }
        public CampoAclaracion obligatorio() { return construir(true, null); }
        public CampoAclaracion opcional(String ayuda) { return construir(false, ayuda); }
        public CampoAclaracion conOpciones(List<OpcionAclaracion> opciones) {
            return new CampoAclaracion(id, etiqueta, SELECT, true, null, opciones, null, null, null, null);
        }

        private CampoAclaracion construir(boolean obligatorio, String ayuda) {
            var opciones = switch (this) {
                case COMPENSACION, TIPO_ACTUAL, NUEVO_TIPO -> COMPENSACIONES;
                case ESTADO -> List.of(new OpcionAclaracion("activar", "Activar"),
                        new OpcionAclaracion("desactivar", "Desactivar"));
                case PDF_TIPO -> List.of(new OpcionAclaracion("todos", "Ambos roles"),
                        new OpcionAclaracion("conductor", "Conductor"), new OpcionAclaracion("pasajero", "Pasajero"));
                default -> null;
            };
            Integer maximo = tipo == NUMBER ? Integer.valueOf(this == PDF_ANIO ? 9999 : Integer.MAX_VALUE) : null;
            return new CampoAclaracion(id, etiqueta, tipo, obligatorio, null, opciones, tipo == NUMBER ? 1 : null,
                    maximo, longitudMaxima, ayuda);
        }
    }
}
