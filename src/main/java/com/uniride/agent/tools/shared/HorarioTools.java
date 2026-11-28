package com.uniride.agent.tools.shared;

import static com.uniride.agent.conversation.Aclaraciones.Campo.*;
import static com.uniride.services.HorarioAcademicoService.canonizarDia;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.CursoResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.OpcionAclaracion;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HorarioTools {

    private static final int PAGE_SIZE = 10;
    public static final String CONTEXTO_CURSO = "seleccionCurso";

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
            Prepara agregar una clase o asignatura al horario académico. Envía solo el nombre,
            día, inicio y fin conocidos; Java pide los datos que falten antes de preparar la propuesta.
            """)
    public OperacionResumen registrarCurso(
            @ToolParam(description = "Nombre del curso", required = false) String nombre,
            @ToolParam(description = "Día de la semana (Lunes a Domingo)", required = false) String dia,
            @ToolParam(description = "Hora de inicio HH:mm en 24 horas", required = false) LocalTime horaInicio,
            @ToolParam(description = "Hora de fin HH:mm en 24 horas", required = false) LocalTime horaFin) {
        String diaCanonico = canonizarDia(dia);
        List<CampoAclaracion> faltantes = new ArrayList<>();
        if (blanco(nombre)) faltantes.add(NOMBRE_CURSO.obligatorio());
        if (diaCanonico == null) faltantes.add(DIA_CURSO.obligatorio());
        if (horaInicio == null) faltantes.add(HORA_INICIO.obligatorio());
        if (horaFin == null) faltantes.add(HORA_FIN.obligatorio());
        if (!faltantes.isEmpty()) return Aclaraciones.faltantes("registrarCurso", faltantes);
        try {
            return confirmaciones.prepararRegistrarCurso(
                    new CursoRequest(nombre.strip(), diaCanonico, horaInicio, horaFin));
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage())
                    .conAclaracion(Aclaraciones.alternativa("registrarCurso", HORA_INICIO, HORA_FIN));
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
            LocalTime horaInicio, ToolContext contexto) {
        if (contexto != null && contexto.getContext().get(CONTEXTO_CURSO) instanceof Map<?, ?> campos
                && campos.get("curso") instanceof String id) {
            return prepararEliminar(Long.valueOf(id));
        }
        if (blanco(nombre) && blanco(dia) && horaInicio == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica qué curso de tu horario quieres eliminar.")
                    .conAclaracion(Aclaraciones.alternativa("eliminarCurso", NOMBRE_CURSO, DIA_CURSO, HORA_INICIO));
        }
        String diaClave = null;
        if (!blanco(dia)) {
            String diaCanonico = canonizarDia(dia);
            if (diaCanonico == null) {
                return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                        "Elige un día de lunes a domingo.")
                        .conAclaracion(Aclaraciones.crear("eliminarCurso", DIA_CURSO.obligatorio()));
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
                .sorted(Comparator.comparing(Curso::getDia).thenComparing(Curso::getHoraInicio).thenComparing(Curso::getId))
                .toList();

        if (coincidencias.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null,
                    "No encontré ningún curso con esos datos en tu horario académico.");
        }
        if (coincidencias.size() > 1) {
            var opciones = coincidencias.stream().limit(PAGE_SIZE).map(c -> {
                String nombreSeguro = com.uniride.agent.dto.AclaracionAgente.textoSeguro(c.getNombre(), 80)
                        ? c.getNombre() : "Curso";
                return new OpcionAclaracion(c.getId().toString(), nombreSeguro + " (" + c.getDia() + " " + c.getHoraInicio() + ")");
            }).toList();
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Encontré varios cursos que coinciden. Identifica cuál quieres eliminar.")
                    .conAclaracion(coincidencias.size() <= PAGE_SIZE
                            ? Aclaraciones.crear("eliminarCurso", CURSO.conOpciones(opciones)).conOpcionesRapidas()
                            : Aclaraciones.alternativa("eliminarCurso", NOMBRE_CURSO, DIA_CURSO, HORA_INICIO));
        }
        return prepararEliminar(coincidencias.getFirst().getId());
    }

    public OperacionResumen eliminarCurso(String nombre, String dia, LocalTime horaInicio) {
        return eliminarCurso(nombre, dia, horaInicio, null);
    }

    private OperacionResumen prepararEliminar(Long curso) {
        try {
            return confirmaciones.prepararEliminarCurso(curso);
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

    private static String normalizarTexto(String texto) {
        return com.uniride.agent.support.AgentText.sinAcentos(texto.strip());
    }

    private static boolean blanco(String valor) {
        return valor == null || valor.isBlank();
    }
}
