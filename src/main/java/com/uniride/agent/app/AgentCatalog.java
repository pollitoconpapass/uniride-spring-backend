package com.uniride.agent.app;

import com.uniride.agent.app.tools.ContribucionAgentTools;
import com.uniride.agent.app.tools.GestionViajeAgentTools;
import com.uniride.agent.app.tools.HorarioAcademicoAgentTools;
import com.uniride.agent.app.tools.MetodoCompensacionAgentTools;
import com.uniride.agent.app.tools.PerfilAgentTools;
import com.uniride.agent.app.tools.ReportesAgentTools;
import com.uniride.agent.app.tools.RutaAgentTools;
import com.uniride.agent.app.tools.SolicitudesConductorAgentTools;
import com.uniride.agent.app.tools.UsuarioAgentTools;
import com.uniride.agent.app.tools.ViajeAgentTools;
import com.uniride.agent.core.AgentDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** La lista de agentes del dominio UniRide. Agregar uno nuevo es agregar un @Bean con su @Order. */
@Configuration
public class AgentCatalog {

    @Bean
    @Order(1)
    AgentDefinition viajes(ViajeAgentTools tools) {
        return new AgentDefinition("viajes",
                "Buscar viajes disponibles y gestionar mis solicitudes como pasajero: ver mis solicitudes, "
                        + "solicitar un cupo y cancelar una solicitud.", tools);
    }

    @Bean
    @Order(2)
    AgentDefinition rutas(RutaAgentTools tools) {
        return new AgentDefinition("rutas",
                "Consultar mis rutas como conductor y preparar su creación, edición o eliminación.", tools);
    }

    @Bean
    @Order(3)
    AgentDefinition compensacion(MetodoCompensacionAgentTools tools) {
        return new AgentDefinition("compensacion",
                "Consultar mis métodos de compensación y preparar su registro, edición, cambio de estado "
                        + "o eliminación.", tools);
    }

    @Bean
    @Order(4)
    AgentDefinition reportes(ReportesAgentTools tools) {
        return new AgentDefinition("reportes",
                "Estadísticas históricas de viajes, frecuencia semanal, ranking de rutas propias y descarga "
                        + "del historial en PDF.", tools);
    }

    @Bean
    @Order(5)
    AgentDefinition contribucion(ContribucionAgentTools tools) {
        return new AgentDefinition("contribucion",
                "Sugerir el aporte estimado de un pasajero para un viaje (dinero, gasolina o favor).", tools);
    }

    @Bean
    @Order(6)
    AgentDefinition perfil(PerfilAgentTools tools) {
        return new AgentDefinition("perfil",
                "Consultar y preparar cambios del perfil personal y de la información adicional "
                        + "(gustos, hobbies, datos curiosos).", tools);
    }

    @Bean
    @Order(7)
    AgentDefinition usuario(UsuarioAgentTools tools) {
        return new AgentDefinition("usuario",
                "Consultar los datos de mi cuenta y preparar el cambio de rol activo entre CONDUCTOR "
                        + "y PASAJERO.", tools);
    }

    @Bean
    @Order(8)
    AgentDefinition horarios(HorarioAcademicoAgentTools tools) {
        return new AgentDefinition("horarios",
                "Consultar, registrar o eliminar cursos de mi horario académico.", tools);
    }

    @Bean
    @Order(9)
    AgentDefinition solicitudesConductor(SolicitudesConductorAgentTools tools) {
        return new AgentDefinition("solicitudesConductor",
                "Gestionar como conductor las solicitudes pendientes de mis viajes: aceptar, rechazar "
                        + "o rechazar todas.", tools);
    }

    @Bean
    @Order(10)
    AgentDefinition gestionViajes(GestionViajeAgentTools tools) {
        return new AgentDefinition("gestionViajes",
                "Gestionar como conductor mis viajes: confirmar, iniciar, completar o cancelar.", tools);
    }
}
