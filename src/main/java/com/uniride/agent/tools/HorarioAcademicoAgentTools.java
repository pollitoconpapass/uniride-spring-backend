package com.uniride.agent.tools;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.CursoResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.PaginaAgente;
import com.uniride.dto.requests.CursoRequest;
import com.uniride.entities.Curso;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.repositories.CursoRepository;
import com.uniride.services.HorarioAcademicoService;
import com.uniride.services.UsuarioService;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HorarioAcademicoAgentTools {

    private static final int PAGE_SIZE = 10;
    private static final Map<String, String> DIAS_CANONICOS = Map.of(
            "lunes", "Lunes",
            "martes", "Martes",
            "miercoles", "Miércoles",
            "jueves", "Jueves",
            "viernes", "Viernes",
            "sabado", "Sábado",
            "domingo", "Domingo");

    private final HorarioAcademicoService horarioAcademicoService;
    private final CursoRepository cursoRepository;
    private final UsuarioService usuarioService;
    private final ConfirmationService confirmaciones;

    @Tool(description = """
            Consulta los cursos registrados en el horario académico del usuario autenticado.
            Permite filtrar por día de la semana (opcional). Devuelve diez por página; pagina inicia en cero.
            """)
    public PaginaAgente<CursoResumen> consultarMisHorarios(
            @ToolParam(description = "Día de la semana opcional (ej. Lunes, Martes, Miércoles)", required = false)
            String dia,
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        int page = validarPagina(pagina);
        if (blanco(dia)) {
            return PaginaAgente.from(horarioAcademicoService.listarCursos(PageRequest.of(page, PAGE_SIZE))
                    .map(c -> new CursoResumen(c.id(), c.nombre(), c.dia(), c.horaInicio(), c.horaFin())));
        }
        String diaNormalizado = canonizarDia(dia);
        if (diaNormalizado == null) {
            throw new CamposInvalidosException(
                    "Día no válido. Usa Lunes, Martes, Miércoles, Jueves, Viernes, Sábado o Domingo.");
        }
        String diaClave = normalizarTexto(diaNormalizado);
        Usuario usuario = usuarioService.usuarioActual();
        List<CursoResumen> filtrados = cursoRepository.buscarPorUsuario(usuario.getId(), Pageable.unpaged())
                .getContent().stream()
                .filter(c -> normalizarTexto(c.getDia()).equals(diaClave))
                .sorted(Comparator.comparing(Curso::getHoraInicio).thenComparing(Curso::getId))
                .map(c -> new CursoResumen(c.getId(), c.getNombre(), c.getDia(), c.getHoraInicio(), c.getHoraFin()))
                .toList();
        int from = Math.min(page * PAGE_SIZE, filtrados.size());
        int to = Math.min(from + PAGE_SIZE, filtrados.size());
        return new PaginaAgente<>(filtrados.subList(from, to), page, PAGE_SIZE, filtrados.size(), to < filtrados.size());
    }

    @Tool(description = """
            Prepara el registro de un curso en el horario académico del usuario.
            NO lo guarda todavía; devuelve una propuesta pendiente de confirmación.
            """)
    public OperacionResumen registrarCurso(
            @ToolParam(description = "Nombre del curso", required = false) String nombre,
            @ToolParam(description = "Día de la semana (Lunes a Domingo)", required = false) String dia,
            @ToolParam(description = "Hora de inicio HH:mm en 24 horas", required = false) LocalTime horaInicio,
            @ToolParam(description = "Hora de fin HH:mm en 24 horas", required = false) LocalTime horaFin) {
        List<String> faltantes = new ArrayList<>();
        if (blanco(nombre)) faltantes.add("nombre del curso");
        if (blanco(dia)) faltantes.add("día de la semana");
        if (horaInicio == null) faltantes.add("hora de inicio");
        if (horaFin == null) faltantes.add("hora de fin");
        if (!faltantes.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Para registrar el curso faltan: " + String.join(", ", faltantes) + ".");
        }
        String diaCanonico = canonizarDia(dia);
        if (diaCanonico == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Día no válido. Usa Lunes, Martes, Miércoles, Jueves, Viernes, Sábado o Domingo.");
        }
        if (!horaFin.isAfter(horaInicio)) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "La hora de fin debe ser posterior a la hora de inicio.");
        }
        try {
            return confirmaciones.prepararRegistrarCurso(
                    new CursoRequest(nombre.trim(), diaCanonico, horaInicio, horaFin));
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara la eliminación de un curso del horario académico del usuario.
            Identifica el curso por nombre y, si hay varios horarios, por día u hora de inicio.
            NO elimina el curso hasta que el usuario confirme la propuesta.
            """)
    public OperacionResumen eliminarCurso(
            @ToolParam(description = "Nombre o parte del nombre del curso", required = false) String nombre,
            @ToolParam(description = "Día de la semana, opcional si hay varios horarios", required = false) String dia,
            @ToolParam(description = "Hora de inicio HH:mm, opcional si hay ambigüedad", required = false)
            LocalTime horaInicio) {
        if (blanco(nombre) && blanco(dia) && horaInicio == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el nombre del curso que deseas eliminar.");
        }
        String diaClave = null;
        if (!blanco(dia)) {
            String diaCanonico = canonizarDia(dia);
            if (diaCanonico == null) {
                return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                        "Día no válido. Usa Lunes, Martes, Miércoles, Jueves, Viernes, Sábado o Domingo.");
            }
            diaClave = normalizarTexto(diaCanonico);
        }
        String nombreClave = blanco(nombre) ? null : normalizarTexto(nombre);
        Usuario usuario = usuarioService.usuarioActual();
        String diaFiltro = diaClave;
        List<Curso> coincidencias = cursoRepository.buscarPorUsuario(usuario.getId(), Pageable.unpaged())
                .getContent().stream()
                .filter(c -> nombreClave == null || normalizarTexto(c.getNombre()).contains(nombreClave))
                .filter(c -> diaFiltro == null || normalizarTexto(c.getDia()).equals(diaFiltro))
                .filter(c -> horaInicio == null || horaInicio.equals(c.getHoraInicio()))
                .toList();

        if (coincidencias.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré ningún curso con esos datos en tu horario académico.");
        }
        if (coincidencias.size() > 1) {
            String opciones = coincidencias.stream().limit(6)
                    .map(c -> "«" + c.getNombre() + "» (" + c.getDia() + " " + c.getHoraInicio()
                            + " - " + c.getHoraFin() + ")")
                    .collect(Collectors.joining("; "));
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Encontré varios cursos que coinciden: " + opciones
                            + ". Indica el día o la hora de inicio para identificar cuál eliminar.");
        }
        try {
            return confirmaciones.prepararEliminarCurso(coincidencias.getFirst().getId());
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private int validarPagina(Integer requestedPage) {
        int page = requestedPage == null ? 0 : requestedPage;
        if (page < 0 || page > 10000) {
            throw new CamposInvalidosException("La página del agente debe estar entre 0 y 10000.");
        }
        return page;
    }

    private static String canonizarDia(String dia) {
        if (blanco(dia)) return null;
        return DIAS_CANONICOS.get(normalizarTexto(dia));
    }

    private static String normalizarTexto(String texto) {
        String n = Normalizer.normalize(texto.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    private static boolean blanco(String valor) {
        return valor == null || valor.isBlank();
    }
}
