package com.uniride.agent;

import com.uniride.agent.dto.CursoResumen;
import com.uniride.agent.dto.PerfilAgenteRespuesta;
import com.uniride.agent.dto.ResultadoAgente;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.dto.responses.UsuarioRespuesta;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Presents profile, account, and academic schedule results captured from Java tools. */
final class AgentPersonalSummary {
    private AgentPersonalSummary() { }

    static String describe(ResultadoAgente result, ObjectMapper mapper) {
        return switch (result.tipo()) {
            case "consultarMiPerfil" -> perfil(mapper.treeToValue(result.datos(), PerfilAgenteRespuesta.class));
            case "consultarMiUsuario" -> usuario(mapper.treeToValue(result.datos(), UsuarioRespuesta.class));
            case "consultarMisHorarios" -> horarios(result.datos(), mapper);
            default -> null;
        };
    }

    private static String perfil(PerfilAgenteRespuesta datos) {
        if (!datos.completo() || datos.perfil() == null) {
            return datos.mensaje();
        }
        PerfilRespuesta p = datos.perfil();
        List<String> partes = new ArrayList<>();
        partes.add("Tu perfil personal: %s %s, carrera %s en %s, distrito %s"
                .formatted(p.nombre(), p.apellidos(), p.carrera(), p.universidad(), p.distrito()));
        partes.add("Disponibilidad: %s (%s), clases del %s al %s"
                .formatted(p.diasDisponibles(), p.horariosDisponibles(),
                        p.fechaInicioClases(), p.fechaFinClases()));
        if (p.metodoCompensacionFavorito() != null) {
            partes.add("Método favorito: " + p.metodoCompensacionFavorito());
        }
        if (noBlanco(p.gustos())) partes.add("Gustos: " + p.gustos());
        if (noBlanco(p.hobbies())) partes.add("Hobbies: " + p.hobbies());
        if (noBlanco(p.datosCuriosos())) partes.add("Datos curiosos: " + p.datosCuriosos());
        return String.join(". ", partes) + ".";
    }

    private static String usuario(UsuarioRespuesta u) {
        return "Tu cuenta: %s %s (%s), teléfono %s, rol activo %s, cuenta %s."
                .formatted(u.nombre(), u.apellidos(), u.correo(), u.telefono(),
                        u.rol() == null ? "sin rol asignado" : u.rol().name(),
                        u.cuentaVerificada() ? "verificada" : "pendiente de verificación");
    }

    private static String horarios(JsonNode datos, ObjectMapper mapper) {
        JsonNode itemsNode = datos.path("items");
        List<CursoResumen> cursos = new ArrayList<>();
        itemsNode.forEach(item -> cursos.add(mapper.treeToValue(item, CursoResumen.class)));
        if (cursos.isEmpty()) {
            return "No encontré cursos registrados en tu horario académico para esa consulta.";
        }
        String lista = cursos.stream()
                .map(c -> "%s (%s %s - %s)".formatted(c.nombre(), c.dia(), c.horaInicio(), c.horaFin()))
                .collect(Collectors.joining("; "));
        boolean hayMas = datos.path("hayMas").asBoolean(false);
        return (cursos.size() == 1 ? "Curso registrado: " : "Cursos registrados (" + cursos.size() + "): ")
                + lista + "." + (hayMas ? " Hay más cursos; puedes pedir la siguiente página." : "");
    }

    private static boolean noBlanco(String value) {
        return value != null && !value.isBlank();
    }
}
