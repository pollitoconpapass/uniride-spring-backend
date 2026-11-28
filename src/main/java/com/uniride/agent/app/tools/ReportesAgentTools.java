package com.uniride.agent.app.tools;

import com.uniride.agent.app.dto.DescargaHistorialPdfRespuesta;
import com.uniride.agent.app.intent.HistorialPdfIntent.Filtros;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.enums.Rol;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.services.EstadisticasService;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class ReportesAgentTools {
    public static final String DESCARGA_HISTORIAL_PDF = "prepararDescargaHistorialPdf";
    public static final String CONTEXTO_FILTROS_PDF = "filtrosHistorialPdf";
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu",
            Locale.forLanguageTag("es-PE"));

    private final EstadisticasService estadisticas;
    private final UsuarioService usuarios;

    @Tool(description = """
            Consulta los totales históricos propios de viajes completados como conductor y pasajero,
            viajes cancelados y penalidades acumuladas. Disponible para ambos roles, sin filtros por fechas.
            """)
    public EstadisticasRespuesta consultarMisEstadisticas() {
        return estadisticas.resumen();
    }

    @Tool(description = """
            Consulta la frecuencia histórica propia de viajes completados por día de la semana, incluidos
            los días sin viajes, y las cancelaciones propias y de terceros. Disponible para ambos roles.
            Requiere cinco viajes completados para mostrar la distribución. No admite filtros por fechas.
            """)
    public AnalisisSemanalRespuesta consultarMiFrecuenciaViajes() {
        return estadisticas.analisisSemanal();
    }

    @Tool(description = """
            Consulta el ranking histórico propio de rutas por viajes completados y sus penalidades como
            conductor. Requiere rol activo CONDUCTOR y cinco viajes completados como conductor.
            No admite filtros por fechas.
            """)
    public RankingRutasRespuesta consultarRankingMisRutas() {
        if (usuarios.usuarioActual().getRolPrincipal() != Rol.CONDUCTOR) {
            throw new NoPermitidoException("Esta consulta requiere el rol CONDUCTOR.");
        }
        return estadisticas.rankingRutas();
    }

    @Tool(name = DESCARGA_HISTORIAL_PDF, description = """
            Prepara una solicitud de descarga autenticada del historial personal de viajes en PDF.
            Úsala ante una solicitud explícita de exportar o descargar el historial. Java resuelve los filtros
            del mensaje actual. Invócala sin argumentos. El archivo se genera al descargarlo.
            """)
    public DescargaHistorialPdfRespuesta prepararDescargaHistorialPdf(ToolContext contexto) {
        usuarios.usuarioActual();
        if (contexto == null || !(contexto.getContext().get(CONTEXTO_FILTROS_PDF) instanceof Filtros filtros)) {
            throw new CamposInvalidosException("Indica qué período de tu historial de viajes quieres descargar.");
        }

        LocalDate desde = filtros.desde();
        LocalDate hasta = filtros.hasta();
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new CamposInvalidosException("La fecha 'desde' no puede ser posterior a la fecha 'hasta'");
        }
        String tipo = filtros.tipo() == null ? "todos" : filtros.tipo().trim().toLowerCase(Locale.ROOT);
        if (!tipo.equals("todos") && !tipo.equals("conductor") && !tipo.equals("pasajero")) {
            throw new CamposInvalidosException("El parámetro tipo debe ser 'todos', 'conductor' o 'pasajero'");
        }

        var url = UriComponentsBuilder.fromPath("/api/historial/pdf");
        if (desde != null) url.queryParam("desde", desde.toString());
        if (hasta != null) url.queryParam("hasta", hasta.toString());
        url.queryParam("tipo", tipo);

        String rol = tipo.equals("todos") ? "" : " como " + tipo;
        String periodo = "";
        if (desde != null && hasta != null) {
            periodo = " del " + FECHA.format(desde) + " al " + FECHA.format(hasta);
        } else if (desde != null) {
            periodo = " desde el " + FECHA.format(desde);
        } else if (hasta != null) {
            periodo = " hasta el " + FECHA.format(hasta);
        }
        String mensaje = "Preparé la solicitud para descargar tu historial de viajes%s%s. "
                .formatted(rol, periodo) + "El PDF se generará cuando solicites la descarga autenticada.";
        return new DescargaHistorialPdfRespuesta("historial-viajes.pdf", url.build().encode().toUriString(),
                desde == null ? null : desde.toString(), hasta == null ? null : hasta.toString(), tipo, mensaje);
    }
}
