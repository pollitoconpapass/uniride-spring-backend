package com.uniride.agent.tools;

import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.enums.Rol;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.services.EstadisticasService;
import com.uniride.services.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportesAgentTools {
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
}
