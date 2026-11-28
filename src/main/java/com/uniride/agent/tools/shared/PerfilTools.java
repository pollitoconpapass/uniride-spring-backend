package com.uniride.agent.tools.shared;

import static com.uniride.agent.conversation.Aclaraciones.Campo.*;

import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.PerfilAgenteRespuesta;
import com.uniride.dto.requests.CambiarRolRequest;
import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.dto.requests.PerfilRequest;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.PerfilService;
import com.uniride.services.UsuarioService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PerfilTools {

    private final PerfilService perfilService;
    private final UsuarioService usuarioService;
    private final ConfirmationService confirmaciones;

    @Tool(description = """
            Consulta los datos personales, académicos y de información adicional del perfil del usuario autenticado
            (nombre, apellidos, carrera, distrito, universidad, días y horarios disponibles, fechas de clases,
            método de compensación favorito, gustos, hobbies y datos curiosos).
            """)
    public PerfilAgenteRespuesta consultarMiPerfil() {
        try {
            return new PerfilAgenteRespuesta(true, null, perfilService.obtenerPerfil());
        } catch (ResourceNotFoundException e) {
            return new PerfilAgenteRespuesta(false,
                    "Aún no has completado tu perfil personal. Puedes completarlo desde la aplicación "
                            + "o indicarme tus datos personales.",
                    null);
        }
    }

    @Tool(description = """
            Prepara cambios en datos personales, académicos o disponibilidad, incluido el método favorito.
            Para «editar mis datos» invoca sin argumentos y Java preguntará qué campo cambiar.
            Los gustos y hobbies se editan con editarInfoAdicional; gestionar un método registrado es otra operación.
            """)
    public OperacionResumen editarPerfil(
            @ToolParam(description = "Nuevo nombre", required = false) String nombre,
            @ToolParam(description = "Nuevos apellidos", required = false) String apellidos,
            @ToolParam(description = "Nueva carrera", required = false) String carrera,
            @ToolParam(description = "Nuevo distrito", required = false) String distrito,
            @ToolParam(description = "Nueva universidad", required = false) String universidad,
            @ToolParam(description = "Nuevos días disponibles", required = false) String diasDisponibles,
            @ToolParam(description = "Nuevos horarios disponibles", required = false) String horariosDisponibles,
            @ToolParam(description = "Fecha inicio de clases yyyy-MM-dd", required = false) LocalDate fechaInicioClases,
            @ToolParam(description = "Fecha fin de clases yyyy-MM-dd", required = false) LocalDate fechaFinClases,
            @ToolParam(description = "Método de compensación favorito: DINERO, GASOLINA o FAVOR", required = false)
            TipoCompensacion metodoCompensacionFavorito) {
        String nNombre = limpiar(nombre);
        String nApellidos = limpiar(apellidos);
        String nCarrera = limpiar(carrera);
        String nDistrito = limpiar(distrito);
        String nUniversidad = limpiar(universidad);
        String nDias = limpiar(diasDisponibles);
        String nHorarios = limpiar(horariosDisponibles);

        if (nNombre == null && nApellidos == null && nCarrera == null && nDistrito == null
                && nUniversidad == null && nDias == null && nHorarios == null
                && fechaInicioClases == null && fechaFinClases == null && metodoCompensacionFavorito == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica qué dato de tu perfil deseas modificar (nombre, apellidos, carrera, distrito, "
                            + "universidad, días u horarios disponibles, fechas de clases o método de compensación favorito).")
                    .conAclaracion(Aclaraciones.crear("editarPerfil", CAMPO_PERFIL.obligatorio()));
        }

        PerfilRespuesta actual = obtenerPerfilOpcional();
        if (actual == null) {
            List<Campo> faltantes = faltantesNuevoPerfil(nNombre, nApellidos, nCarrera, nDistrito, nUniversidad,
                    nDias, nHorarios, fechaInicioClases, fechaFinClases, metodoCompensacionFavorito);
            if (!faltantes.isEmpty()) {
                return Aclaraciones.faltantes("editarPerfil", faltantes.stream().limit(8).map(Campo::obligatorio).toList());
            }
        }

        PerfilRequest request = new PerfilRequest(
                nNombre != null ? nNombre : actual.nombre(),
                nApellidos != null ? nApellidos : actual.apellidos(),
                nCarrera != null ? nCarrera : actual.carrera(),
                nDistrito != null ? nDistrito : actual.distrito(),
                nUniversidad != null ? nUniversidad : actual.universidad(),
                nDias != null ? nDias : actual.diasDisponibles(),
                nHorarios != null ? nHorarios : actual.horariosDisponibles(),
                fechaInicioClases != null ? fechaInicioClases : actual.fechaInicioClases(),
                fechaFinClases != null ? fechaFinClases : actual.fechaFinClases(),
                metodoCompensacionFavorito != null
                        ? metodoCompensacionFavorito
                        : (actual == null ? null : actual.metodoCompensacionFavorito()));

        List<String> cambios = describirCambiosPerfil(actual, request);
        if (actual != null && cambios.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, actual.id(),
                    "Tu perfil personal ya tiene los valores solicitados.");
        }

        String resumen = actual == null
                ? "Completar tu perfil personal como " + request.nombre() + " " + request.apellidos()
                + " (" + request.carrera() + ", " + request.distrito() + ", " + request.universidad() + ")."
                : "Actualizar tu perfil personal: " + String.join(", ", cambios) + ".";

        try {
            return confirmaciones.prepararEditarPerfil(request, resumen);
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    @Tool(description = """
            Prepara la actualización de gustos, hobbies o datos curiosos del perfil del usuario.
            Si el usuario pide agregar un elemento a lo que ya tiene, pasa agregar=true; si pide reemplazarlo, agregar=false.
            NO aplica cambios todavía; devuelve una propuesta pendiente de confirmación.
            """)
    public OperacionResumen editarInfoAdicional(
            @ToolParam(description = "Gustos del usuario o elemento a agregar", required = false) String gustos,
            @ToolParam(description = "Hobbies del usuario o elemento a agregar", required = false) String hobbies,
            @ToolParam(description = "Datos curiosos o elemento a agregar", required = false) String datosCuriosos,
            @ToolParam(description = "true para agregar a lo existente; false para reemplazar", required = false)
            Boolean agregar) {
        String nGustos = limpiar(gustos);
        String nHobbies = limpiar(hobbies);
        String nDatos = limpiar(datosCuriosos);

        if (nGustos == null && nHobbies == null && nDatos == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica qué deseas actualizar en tus gustos, hobbies o datos curiosos.")
                    .conAclaracion(Aclaraciones.alternativa("editarInfoAdicional", GUSTOS, HOBBIES, DATOS_CURIOSOS));
        }

        PerfilRespuesta actual = obtenerPerfilOpcional();
        if (actual == null) {
            return new OperacionResumen(EstadoOperacion.ERROR, null,
                    "Primero completa tu perfil personal antes de editar tu información adicional.");
        }

        boolean modoAgregar = Boolean.TRUE.equals(agregar);
        String gustosFinal = combinar(nGustos, actual.gustos(), modoAgregar);
        String hobbiesFinal = combinar(nHobbies, actual.hobbies(), modoAgregar);
        String datosFinal = combinar(nDatos, actual.datosCuriosos(), modoAgregar);

        List<String> cambios = new ArrayList<>();
        if (nGustos != null && !Objects.equals(gustosFinal, limpiar(actual.gustos()))) {
            cambios.add("gustos → «" + gustosFinal + "»");
        }
        if (nHobbies != null && !Objects.equals(hobbiesFinal, limpiar(actual.hobbies()))) {
            cambios.add("hobbies → «" + hobbiesFinal + "»");
        }
        if (nDatos != null && !Objects.equals(datosFinal, limpiar(actual.datosCuriosos()))) {
            cambios.add("datos curiosos → «" + datosFinal + "»");
        }
        if (cambios.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, actual.id(),
                    "Tu información adicional ya incluye esos datos.");
        }

        List<Campo> faltantes = new ArrayList<>();
        if (esBlanco(gustosFinal) && esBlanco(actual.gustos())) faltantes.add(GUSTOS);
        if (esBlanco(hobbiesFinal) && esBlanco(actual.hobbies())) faltantes.add(HOBBIES);
        if (esBlanco(datosFinal) && esBlanco(actual.datosCuriosos())) faltantes.add(DATOS_CURIOSOS);
        if (!faltantes.isEmpty()) {
            return Aclaraciones.faltantes("editarInfoAdicional", faltantes.stream().map(Campo::obligatorio).toList());
        }

        InfoAdicionalRequest request = new InfoAdicionalRequest(
                nGustos != null ? gustosFinal : null,
                nHobbies != null ? hobbiesFinal : null,
                nDatos != null ? datosFinal : null);
        String resumen = "Actualizar tu información adicional: " + String.join(", ", cambios) + ".";

        try {
            return confirmaciones.prepararEditarInfoAdicional(request, resumen);
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private PerfilRespuesta obtenerPerfilOpcional() {
        try {
            return perfilService.obtenerPerfil();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private List<Campo> faltantesNuevoPerfil(String nombre, String apellidos, String carrera, String distrito,
            String universidad, String dias, String horarios, LocalDate inicio, LocalDate fin,
            TipoCompensacion metodo) {
        List<Campo> faltantes = new ArrayList<>();
        if (nombre == null) faltantes.add(NOMBRE);
        if (apellidos == null) faltantes.add(APELLIDOS);
        if (carrera == null) faltantes.add(CARRERA);
        if (distrito == null) faltantes.add(DISTRITO);
        if (universidad == null) faltantes.add(UNIVERSIDAD);
        if (dias == null) faltantes.add(DIAS_DISPONIBLES);
        if (horarios == null) faltantes.add(HORARIOS_DISPONIBLES);
        if (inicio == null) faltantes.add(INICIO_CLASES);
        if (fin == null) faltantes.add(FIN_CLASES);
        if (metodo == null && usuarioService.usuarioActual().getRolPrincipal() == Rol.CONDUCTOR) {
            faltantes.add(FAVORITO);
        }
        return faltantes;
    }

    private List<String> describirCambiosPerfil(PerfilRespuesta actual, PerfilRequest request) {
        if (actual == null) return List.of();
        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(actual.nombre(), request.nombre())) cambios.add("nombre → " + request.nombre());
        if (!Objects.equals(actual.apellidos(), request.apellidos())) cambios.add("apellidos → " + request.apellidos());
        if (!Objects.equals(actual.carrera(), request.carrera())) cambios.add("carrera → " + request.carrera());
        if (!Objects.equals(actual.distrito(), request.distrito())) cambios.add("distrito → " + request.distrito());
        if (!Objects.equals(actual.universidad(), request.universidad())) {
            cambios.add("universidad → " + request.universidad());
        }
        if (!Objects.equals(actual.diasDisponibles(), request.diasDisponibles())) {
            cambios.add("días disponibles → " + request.diasDisponibles());
        }
        if (!Objects.equals(actual.horariosDisponibles(), request.horariosDisponibles())) {
            cambios.add("horarios disponibles → " + request.horariosDisponibles());
        }
        if (!Objects.equals(actual.fechaInicioClases(), request.fechaInicioClases())) {
            cambios.add("inicio de clases → " + request.fechaInicioClases());
        }
        if (!Objects.equals(actual.fechaFinClases(), request.fechaFinClases())) {
            cambios.add("fin de clases → " + request.fechaFinClases());
        }
        if (!Objects.equals(actual.metodoCompensacionFavorito(), request.metodoCompensacionFavorito())) {
            cambios.add("método favorito → " + request.metodoCompensacionFavorito());
        }
        return cambios;
    }

    private String combinar(String nuevo, String actual, boolean agregar) {
        if (nuevo == null) return null;
        String base = limpiar(actual);
        if (!agregar || base == null) return nuevo;
        if (base.toLowerCase(Locale.ROOT).contains(nuevo.toLowerCase(Locale.ROOT))) {
            return base;
        }
        return base + ", " + nuevo;
    }

    private static String limpiar(String valor) {
        if (valor == null) return null;
        String trimmed = valor.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean esBlanco(String valor) {
        return valor == null || valor.isBlank();
    }

    @Tool(description = """
            Consulta los datos de la cuenta del usuario autenticado (nombre, apellidos, correo institucional,
            teléfono, rol activo y estado de verificación).
            """)
    public UsuarioRespuesta consultarMiUsuario() {
        return usuarioService.obtenerUsuarioActual();
    }

    @Tool(description = """
            Prepara el cambio del rol activo del usuario entre CONDUCTOR y PASAJERO.
            NO cambia el rol todavía; devuelve una propuesta que requiere confirmación del usuario.
            """)
    public OperacionResumen cambiarRol(
            @ToolParam(description = "Nuevo rol activo: CONDUCTOR o PASAJERO", required = false) Rol rol) {
        if (rol == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica si deseas cambiar tu rol activo a CONDUCTOR o PASAJERO.")
                    .conAclaracion(Aclaraciones.crear("cambiarRol", ROL.obligatorio()).conOpcionesRapidas());
        }
        Usuario actual = usuarioService.usuarioActual();
        if (actual.getRolPrincipal() == rol) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, actual.getId(),
                    "Tu rol activo ya es " + rol + ".");
        }
        try {
            return confirmaciones.prepararCambiarRol(new CambiarRolRequest(rol));
        } catch (CamposInvalidosException e) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, e.getMessage());
        } catch (BusinessException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }
}
