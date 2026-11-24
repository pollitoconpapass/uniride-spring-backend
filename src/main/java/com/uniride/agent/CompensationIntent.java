package com.uniride.agent;

import static com.uniride.agent.Aclaraciones.Campo.COMPENSACION;
import static com.uniride.agent.Aclaraciones.Campo.DESCRIPCION;
import static com.uniride.agent.Aclaraciones.Campo.DESCRIPCION_ACTUAL;
import static com.uniride.agent.Aclaraciones.Campo.ESTADO;
import static com.uniride.agent.Aclaraciones.Campo.NUEVA_DESCRIPCION;
import static com.uniride.agent.Aclaraciones.Campo.NUEVO_TIPO;
import static com.uniride.agent.Aclaraciones.Campo.TIPO_ACTUAL;

import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.enums.TipoCompensacion;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Recognizes explicit compensation requests without taking arguments from the provider. */
final class CompensationIntent {
    private static final String REGISTER = "registrarMetodoCompensacion";
    private static final String STATE = "cambiarEstadoMetodoCompensacion";
    private static final String EDIT = "actualizarMetodoCompensacion";
    private static final String DELETE = "eliminarMetodoCompensacion";
    private static final String TYPE = "DINERO|GASOLINA|FAVOR";
    private static final String REQUEST = "(?:por favor,? )?(?:(?:quiero|necesito|puedes|podr[ií]as) )?";
    private static final String HELP = "Para gestionar un método, indica la acción y el tipo o la descripción del método. "
            + "Los cambios se aplican al confirmar el resumen desde la aplicación. Solo puedes eliminar métodos sin solicitudes asociadas.";
    private static final String SELECTOR = "¿Qué método de compensación quieres usar? Indica un solo tipo o una descripción exacta.";
    private static final String TYPE_QUESTION = "¿Qué tipo quieres para el nuevo método: DINERO, GASOLINA o FAVOR?";
    private static final String EDIT_QUESTION = "¿Qué quieres cambiar en tu método de compensación? Indica el nuevo tipo o la nueva descripción.";
    private static final String TYPES_HELP = "UniRide admite DINERO para aportes monetarios, GASOLINA para aportes de combustible "
            + "y FAVOR para ayudas acordadas entre usuarios.";
    private static final String MANAGEMENT_HELP = "Para registrar un método, indica el tipo y la descripción. "
            + "Para actualizarlo, identifica tu método y especifica el nuevo tipo o descripción. "
            + "Para eliminarlo, identifica un método propio sin solicitudes asociadas. En cada caso, revisa y confirma la propuesta desde la aplicación.";
    private static final Decision OTHER = new Decision(null, null, null, null, false, false);

    private static final Pattern METHOD = pattern("^(?:(?:mi|el|un|este|ese) )?(?:nuevo )?m[eé]todo(?: de compensaci[oó]n)?(?: (.*))?$");
    private static final Pattern OWNED_NAME = pattern("^mi (.+)$");
    private static final Pattern OTHER_RESOURCE = pattern("^(?:cuenta|perfil|ruta|viaje|solicitud|cupo|reserva|"
            + "sesi[oó]n|ubicaci[oó]n|notificaciones?|alertas?|historial|modo|suscripci[oó]n|"
            + "opci[oó]n|servicio|conversaci[oó]n|contrase[nñ]a|c[oó]digo)\\b");
    private static final Pattern TYPE_AND_REST = pattern("^(?:(?:de tipo|tipo|de) )?(" + TYPE + ")(?:(?:, *| +)(.+))?$");
    private static final Pattern CHANGE_STATE = pattern("^" + REQUEST
            + "(activa(?:r)?|desactiva(?:r)?)(?: nuevamente| de nuevo| otra vez)?(?: (.+))?$");
    private static final Pattern CHOOSE_STATE = pattern("^" + REQUEST
            + "(?:cambia(?:r)?|modifica(?:r)?|actualiza(?:r)?) (?:el )?estado (?:de )?(.+)$");
    private static final Pattern REGISTER_REQUEST = pattern("^" + REQUEST
            + "(?:reg[ií]stra(?:r|me)?|agrega(?:r|me)?|a[nñ]ade(?:r)?)(?: (.+))?$");
    private static final Pattern ADD_PREFERENCE = pattern("^prefiero (?:compensar con )?(" + TYPE
            + ")(?:, *| +)(?:agr[eé]galo|reg[ií]stralo|a[nñ][aá]delo)(?: (.+))?$");
    private static final Pattern EDIT_REQUEST = pattern("^" + REQUEST + "(?:cambia(?:r)?|edita(?:r)?|modifica(?:r)?|actualiza(?:r)?) (.+)$");
    private static final Pattern DELETE_REQUEST = pattern("^" + REQUEST + "(?:elimina(?:r)?|borra(?:r)?|quita(?:r)?)(?: (.+))?$");
    private static final Pattern REMOVE_FROM_METHODS = pattern("^(.+) de mis m[eé]todos(?: de compensaci[oó]n)?$");
    private static final Pattern FIELD = pattern("^((?:la )?descripci[oó]n|(?:el )?tipo) de (.+)$");
    private static final Pattern CHANGE = pattern("\"[^\"]*\"|«[^»]*»| (al tipo|a|por|con (?:tipo|(?:nueva )?descripci[oó]n))(?= |$)");
    private static final Pattern DESCRIPTION = pattern("^(?:(?:con )?(?:una )?descripci[oó]n(?: es| de)? *:? *|(?:llamad[oa]|con nombre) )(.+)$");
    private static final Pattern WITH_DESCRIPTION = pattern("^con (?!tipo\\b|descripci[oó]n\\b)(.+)$");
    private static final Pattern EXPLICIT_TYPE = pattern("^(?:(?:al|de|nuevo|el) )?tipo\\b");
    private static final Pattern INFORMATION = pattern("^(?:c[oó]mo|qu[eé] pasa si|expl[ií]ca(?:me)?|puedo|podr[ií]a|debo|es posible)\\b");
    private static final String COMPENSATION_TYPES = "tipos de (?:m[eé]todos? de )?compensaci[oó]n";
    private static final Pattern TYPE_CATALOGUE = pattern("^(?:(?:dime|quiero saber) )?(?:qu[eé] " + COMPENSATION_TYPES
            + "(?: existen| hay| est[aá]n disponibles)?|cu[aá]les son los " + COMPENSATION_TYPES + "(?: disponibles)?)$");
    private static final String METHOD_PROCEDURE = "(?:registrar|registro|actualizar|actualizo|editar|edito|"
            + "activar|activo|desactivar|desactivo|eliminar|elimino|gestionar|administrar)";
    private static final Pattern MANAGEMENT = pattern("^(?:(?:expl[ií]came )?c[oó]mo (?:puedo |se )?|puedo )" + METHOD_PROCEDURE
            + "(?:(?:, *| (?:o|y) )" + METHOD_PROCEDURE + ")* (?:(?:mi|mis|un|los) )?m[eé]todos?(?: de compensaci[oó]n)?$");
    private static final Pattern NEGATION = pattern("^(?:por favor,? )?(?:no|nunca|jam[aá]s)\\b");
    private static final Pattern TRIP = pattern("\\b(?:unirme|unirse|[uú]neme|sumarme|sumarse|subirme|viajar con|"
            + "(?:solicita(?:r|me)?|reserva(?:r|me)?|pide|pedir) (?:un )?cupo)\\b");
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _+/%&()'-]{0,254}");
    private static final Pattern MULTIPLE = pattern("\\b(?:y|o|ni|no|pero|luego|despu[eé]s|adem[aá]s|tambi[eé]n|ambos|todos)\\b");
    private static final Pattern UNSAFE_SELECTOR = pattern("\\b(?:usuario_?id|m[eé]todo_?id|id|de otro usuario|ajeno|de todos)\\b");
    private static final Pattern ACTION_WORD = pattern("\\b(?:activa(?:r)?|desactiva(?:r)?|elimina(?:r)?|borra(?:r)?|quita(?:r)?|"
            + "cambia(?:r)?|edita(?:r)?|modifica(?:r)?|actualiza(?:r)?|reg[ií]stra(?:r)?|agr[eé]ga(?:r|lo)?|a[nñ]ade(?:r)?)\\b");
    private static final Pattern NEW_TOPIC = pattern("^(?:dime|busca(?:r|me)?|mu[eé]stra(?:me)?|consulta(?:r|me)?|"
            + "mis (?:rutas|viajes|solicitudes|m[eé]todos|estad[ií]sticas)|(?:mi|el) historial|quiero|necesito|prefiero|"
            + "mejor|ahora|cambiemos|olvida|cancela(?:r)?|descarga(?:r)?|exporta(?:r)?|crea(?:r)?|"
            + "confirma(?:r)?|cu[aá]ntos?|cu[aá]l(?:es)?|cu[aá]ndo|d[oó]nde|qu[eé])\\b");
    private static final Pattern GREETING = pattern("^(?:hola|gracias|muchas gracias|buenos d[ií]as|buenas tardes|buenas noches|ok(?:ay)?)$");
    private static final Pattern TRAILING_PLEASE = pattern("(?:, ?| )por favor$");
    private static final Pattern TERMINAL_PUNCTUATION = Pattern.compile("^[¿¡]+|[.!?]+$");
    private static final Pattern WHITESPACE = Pattern.compile("[^\\S\\r\\n]+");
    private static final Pattern METHOD_WORD = pattern("\\bm[eé]todos?\\b");
    private static final Pattern REPLY_FIELD = pattern("^([\\p{L} ]{2,40}):\\s*(.+)$");
    private static final Pattern OWN_METHOD_LIST = pattern("^(?:por favor,? )?"
            + "(?:(?:mu[eé]strame|ens[eé][ñn]ame|lista(?:me)?|consulta(?:me)?|dame|"
            + "cu[aá]les son|(?:quiero|quisiera) (?:ver|consultar)|puedes (?:mostrarme|listar|consultar)) )?"
            + "mis m[eé]todos(?: de compensaci[oó]n)?$");

    private CompensationIntent() { }

    sealed interface Draft permits Registro, Edicion, Eliminacion, CambioEstado {
        void validateNoSecrets();
    }

    record Registro(TipoCompensacion tipo, String descripcion) implements Draft {
        @Override public void validateNoSecrets() {
            AgentConversationStore.validateNoSecrets(descripcion);
        }
    }

    private enum Campo { TIPO, DESCRIPCION, SIN_INDICAR }

    private record Selector(TipoCompensacion tipo, String descripcion) {
        void addTo(Map<String, Object> arguments) {
            if (tipo != null) arguments.put("tipoActual", tipo);
            if (descripcion != null) arguments.put("descripcionActual", descripcion);
        }
    }

    private record Cambio(TipoCompensacion tipo, String descripcion) {
        void addTo(Map<String, Object> arguments) {
            if (tipo != null) arguments.put("nuevoTipo", tipo);
            if (descripcion != null) arguments.put("nuevaDescripcion", descripcion);
        }
    }

    private record Edicion(Selector selector, Campo campo, Cambio cambio) implements Draft {
        @Override public void validateNoSecrets() {
            if (selector != null) AgentConversationStore.validateNoSecrets(selector.descripcion());
            if (cambio != null) AgentConversationStore.validateNoSecrets(cambio.descripcion());
        }
    }

    private record Eliminacion(Selector selector) implements Draft {
        @Override public void validateNoSecrets() {
            if (selector != null) AgentConversationStore.validateNoSecrets(selector.descripcion());
        }
    }

    private record CambioEstado(Selector selector, Boolean activo) implements Draft {
        @Override public void validateNoSecrets() {
            if (selector != null) AgentConversationStore.validateNoSecrets(selector.descripcion());
        }
    }

    record Decision(String allowedTool, Map<String, Object> recoveryArgs, String message,
                    Draft draft, boolean blockPreparations, boolean informational) {
        AclaracionAgente aclaracion() {
            return switch (draft) {
                case Registro(var tipo, var descripcion) -> individual(REGISTER,
                        tipo == null ? COMPENSACION.obligatorio() : null,
                        descripcion == null ? DESCRIPCION.obligatorio() : null);
                case Edicion(var selector, var campo, var cambio) -> {
                    if (selector == null || cambio != null) yield selectorForm(EDIT, selector);
                    yield switch (campo) {
                        case TIPO -> individual(EDIT, NUEVO_TIPO.obligatorio());
                        case DESCRIPCION -> individual(EDIT, NUEVA_DESCRIPCION.obligatorio());
                        case SIN_INDICAR -> Aclaraciones.alternativa(EDIT, NUEVO_TIPO, NUEVA_DESCRIPCION);
                    };
                }
                case Eliminacion(var selector) -> selectorForm(DELETE, selector);
                case CambioEstado(var selector, var activo) -> {
                    if (activo == null && selector != null) yield individual(STATE, ESTADO.obligatorio());
                    if (activo == null) yield Aclaraciones.crear(STATE, ESTADO.obligatorio(),
                            TIPO_ACTUAL.opcional("Completa el tipo o la descripción del método actual."),
                            DESCRIPCION_ACTUAL.opcional("Completa la descripción o el tipo del método actual."));
                    yield selectorForm(STATE, selector);
                }
                case null -> null;
            };
        }
    }

    private static AclaracionAgente individual(String operacion, CampoAclaracion... campos) {
        var presentes = java.util.Arrays.stream(campos).filter(java.util.Objects::nonNull).toArray(CampoAclaracion[]::new);
        var esquema = Aclaraciones.crear(operacion, presentes);
        return presentes.length == 1 ? esquema.conRespuestaBreve("valor") : esquema;
    }

    private static AclaracionAgente selectorForm(String operacion, Selector selector) {
        if (selector != null && selector.tipo() != null) return individual(operacion, DESCRIPCION_ACTUAL.obligatorio());
        return Aclaraciones.alternativa(operacion, TIPO_ACTUAL, DESCRIPCION_ACTUAL);
    }

    static Decision classify(String message, Draft draft) {
        String text = clean(message);
        if (AgentConversationStore.containsSecrets(message) || ConfirmationIntent.classify(message) != ConfirmationIntent.OTRA
                || NEGATION.matcher(text).find()) {
            return blocked(null, null);
        }
        if (TYPE_CATALOGUE.matcher(text).matches()) return information(TYPES_HELP);
        if (MANAGEMENT.matcher(text).matches()) return information(MANAGEMENT_HELP);
        if (INFORMATION.matcher(text).find()) {
            return blocked(METHOD_WORD.matcher(text).find() ? HELP : null, null);
        }
        if ((text.contains("\n") || text.contains("\r")) && METHOD_WORD.matcher(text).find()
                && ACTION_WORD.matcher(text).find()) return blocked(SELECTOR, null);

        var preference = ADD_PREFERENCE.matcher(text);
        if (preference.matches()) {
            return registration(readType(preference.group(1)), registrationDescription(preference.group(2)));
        }
        var state = CHANGE_STATE.matcher(text);
        if (state.matches()) {
            if (state.group(2) == null && draft instanceof CambioEstado pendiente && pendiente.activo() == null) {
                boolean activo = state.group(1).toLowerCase(Locale.ROOT).startsWith("activa");
                return pendiente.selector() == null ? stateQuestion(new CambioEstado(null, activo))
                        : stateOperation(pendiente.selector(), activo);
            }
            return changeState(state.group(1), state.group(2));
        }
        var chooseState = CHOOSE_STATE.matcher(text);
        if (chooseState.matches()) {
            String method = ownedSelector(chooseState.group(1));
            if (method != null) {
                var target = selector(method);
                return target != null || method.isBlank() ? stateQuestion(new CambioEstado(target, null)) : blocked(SELECTOR, null);
            }
        }
        var register = REGISTER_REQUEST.matcher(text);
        if (register.matches()) {
            return registerMethod(register.group(1));
        }
        var edit = EDIT_REQUEST.matcher(text);
        if (edit.matches()) {
            return editMethod(edit.group(1));
        }
        var delete = DELETE_REQUEST.matcher(text);
        if (delete.matches()) return deleteMethod(delete.group(1));
        return draft == null ? OTHER : continueDraft(message, text, draft);
    }

    static Draft afterResponse(Decision decision, OperacionResumen operation) {
        if (operation == null || operation.estado() != EstadoOperacion.AMBIGUO || decision.recoveryArgs() == null) {
            return decision.draft();
        }
        var args = decision.recoveryArgs();
        var selector = new Selector((TipoCompensacion) args.get("tipoActual"), (String) args.get("descripcionActual"));
        return switch (decision.allowedTool()) {
            case EDIT -> new Edicion(selector, Campo.SIN_INDICAR,
                    new Cambio((TipoCompensacion) args.get("nuevoTipo"), (String) args.get("nuevaDescripcion")));
            case DELETE -> new Eliminacion(selector);
            case STATE -> new CambioEstado(selector, (Boolean) args.get("activo"));
            default -> decision.draft();
        };
    }

    static boolean isTripRequest(String message) {
        return message != null && TRIP.matcher(message).find();
    }

    static boolean isOwnMethodList(String message) {
        return OWN_METHOD_LIST.matcher(clean(message)).matches();
    }

    private static Decision changeState(String verb, String subject) {
        boolean active = verb.toLowerCase(Locale.ROOT).startsWith("activa");
        String method = ownedSelector(subject);
        if (method == null) {
            if (subject == null) return blocked(SELECTOR, null);
            String type = subject.replaceFirst("(?iu)^(?:mi|el) ", "");
            if (!TYPE_AND_REST.matcher(type).matches()) return OTHER;
            method = type;
        }
        var selector = selector(method);
        if (selector == null) return method.isBlank() ? stateQuestion(new CambioEstado(null, active)) : blocked(SELECTOR, null);
        return stateOperation(selector, active);
    }

    private static Decision stateOperation(Selector selector, boolean active) {
        Map<String, Object> arguments = new HashMap<>();
        selector.addTo(arguments);
        arguments.put("activo", active);
        return ready(STATE, arguments);
    }

    private static Decision stateQuestion(CambioEstado draft) {
        String mensaje = draft.activo() != null ? SELECTOR : draft.selector() == null
                ? "Indica el tipo o la descripción del método y elige si quieres activarlo o desactivarlo."
                : "¿Quieres activar o desactivar el método de compensación?";
        return blocked(mensaje, draft);
    }

    private static Decision registerMethod(String subject) {
        if (subject == null) return OTHER;
        String method = methodText(subject);
        boolean explicitMethod = method != null;
        var type = TYPE_AND_REST.matcher(explicitMethod ? method : subject);
        if (type.matches()) return registration(readType(type.group(1)), registrationDescription(type.group(2)));
        return explicitMethod ? registration(null, registrationDescription(method)) : OTHER;
    }

    private static Decision continueDraft(String original, String text, Draft draft) {
        var field = REPLY_FIELD.matcher(text);
        if (field.matches()) {
            String id = switch (normalizar(field.group(1))) {
                case "tipo de compensacion", "compensacion", "tipo" -> "compensacion";
                case "descripcion" -> "descripcion";
                case "tipo actual" -> "tipoActual";
                case "descripcion actual" -> "descripcionActual";
                case "nuevo tipo" -> "nuevoTipo";
                case "nueva descripcion" -> "nuevaDescripcion";
                case "estado", "accion", "estado deseado" -> "activo";
                default -> null;
            };
            if (id != null) {
                var decision = continuarCampos(Map.of(id, field.group(2)), draft);
                if (decision != null) return decision;
            }
        }
        if (original.contains("?") || original.contains("¿") || NEW_TOPIC.matcher(text).find() || GREETING.matcher(text).matches()
                || ACTION_WORD.matcher(text).find() || OTHER_RESOURCE.matcher(text).find()
                || isTripRequest(text) || !AgentReportIntent.classify(text).isEmpty()) return OTHER;
        return switch (draft) {
            case Registro registration -> continueRegistration(text, registration);
            case Edicion edit -> continueEdit(text, edit);
            case Eliminacion deletion -> {
                var selection = refineSelector(deletion.selector(), text);
                yield selection == null ? OTHER : methodOperation(DELETE, selection, null);
            }
            case CambioEstado state -> {
                if (state.activo() == null) {
                    var active = estadoFormulario(text);
                    if (active != null) yield state.selector() == null
                            ? stateQuestion(new CambioEstado(null, active)) : stateOperation(state.selector(), active);
                }
                var selection = refineSelector(state.selector(), text);
                yield selection == null ? OTHER : state.activo() == null
                        ? stateQuestion(new CambioEstado(selection, null)) : stateOperation(selection, state.activo());
            }
        };
    }

    static Decision continuarCampos(Map<String, String> campos, Draft draft) {
        if (draft == null || campos.isEmpty()) return null;
        var descriptor = blocked(null, draft).aclaracion();
        if (descriptor == null || campos.keySet().stream().anyMatch(id -> descriptor.campos().stream()
                .noneMatch(campo -> campo.id().equals(id)))) return null;

        var tipoActual = tipoFormulario(campos.get("tipoActual"));
        var descripcionActual = descripcionFormulario(campos.get("descripcionActual"));
        if (descripcionActual != null && descripcionActual.matches("\\d+")) descripcionActual = null;
        var selector = tipoActual == null && descripcionActual == null ? null : new Selector(tipoActual, descripcionActual);
        return switch (draft) {
            case Registro(var tipo, var descripcion) -> registration(
                    valor(tipo, tipoFormulario(campos.get("compensacion"))),
                    valor(descripcion, descripcionFormulario(campos.get("descripcion"))));
            case Edicion(var anterior, var campo, var cambio) -> {
                Selector target = combinarSelector(anterior, selector);
                if (cambio == null) {
                    TipoCompensacion nuevoTipo = tipoFormulario(campos.get("nuevoTipo"));
                    String nuevaDescripcion = descripcionFormulario(campos.get("nuevaDescripcion"));
                    if (nuevoTipo != null || nuevaDescripcion != null) cambio = new Cambio(nuevoTipo, nuevaDescripcion);
                }
                yield target == null || cambio == null ? editQuestion(new Edicion(target, campo, cambio))
                        : methodOperation(EDIT, target, cambio);
            }
            case Eliminacion(var anterior) -> {
                Selector target = combinarSelector(anterior, selector);
                yield target == null || selector == null ? blocked(SELECTOR, draft) : methodOperation(DELETE, target, null);
            }
            case CambioEstado(var anterior, var activo) -> {
                Selector target = combinarSelector(anterior, selector);
                var nuevoEstado = valor(activo, estadoFormulario(campos.get("activo")));
                yield target == null || nuevoEstado == null || (selector == null && activo != null)
                        ? stateQuestion(new CambioEstado(target, nuevoEstado)) : stateOperation(target, nuevoEstado);
            }
        };
    }

    static boolean corresponde(String operacion, Draft draft) {
        var esquema = draft == null ? null : blocked(null, draft).aclaracion();
        return esquema != null && esquema.operacion().equals(operacion);
    }

    private static Selector combinarSelector(Selector anterior, Selector nuevo) {
        if (anterior == null) return nuevo;
        if (nuevo == null) return anterior;
        if (nuevo.tipo() != null && anterior.tipo() != null && nuevo.tipo() != anterior.tipo()) return null;
        if (nuevo.descripcion() != null && anterior.descripcion() != null
                && !normalizar(nuevo.descripcion()).contains(normalizar(anterior.descripcion()))) return null;
        return new Selector(valor(nuevo.tipo(), anterior.tipo()), valor(nuevo.descripcion(), anterior.descripcion()));
    }

    private static <T> T valor(T primero, T segundo) { return primero == null ? segundo : primero; }

    private static TipoCompensacion tipoFormulario(String valor) {
        if (valor == null) return null;
        var tipo = TYPE_AND_REST.matcher(valor.strip());
        return tipo.matches() && tipo.group(2) == null ? readType(tipo.group(1)) : null;
    }

    private static Boolean estadoFormulario(String valor) {
        if (valor == null) return null;
        return switch (normalizar(valor)) {
            case "activar" -> true;
            case "desactivar" -> false;
            default -> null;
        };
    }

    private static String descripcionFormulario(String valor) {
        if (valor == null || valor.isBlank() || valor.length() > 255 || valor.chars().anyMatch(Character::isISOControl)
                || AgentConversationStore.containsSecrets(valor)) return null;
        return valor.strip();
    }

    private static String normalizar(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).strip();
    }

    private static Decision continueRegistration(String text, Registro draft) {
        var type = TYPE_AND_REST.matcher(text);
        if (type.matches() && type.group(2) == null) {
            return registration(readType(type.group(1)), draft.descripcion());
        }
        if (draft.descripcion() != null) return OTHER;
        String explicitDescription = registrationDescription(text);
        String description = explicitDescription == null ? label(text) : explicitDescription;
        return description == null ? OTHER : registration(draft.tipo(), description);
    }

    private static Decision registration(TipoCompensacion type, String description) {
        if (type == null) return blocked(description == null
                ? "Indica el tipo de compensación y una descripción para el nuevo método."
                : TYPE_QUESTION, new Registro(null, description));
        if (description == null) {
            return blocked("¿Qué descripción quieres asignar a tu nuevo método " + type + "?", new Registro(type, null));
        }
        return ready(REGISTER, Map.of("tipo", type, "descripcion", description));
    }

    private static Decision editMethod(String subject) {
        var field = FIELD.matcher(subject);
        Campo requested = field.matches() ? fieldFrom(field.group(1)) : Campo.SIN_INDICAR;
        String targetExpression = requested == Campo.SIN_INDICAR ? subject : field.group(2);
        String method = ownedSelector(targetExpression);
        if (method == null && requested != Campo.SIN_INDICAR
                && !OTHER_RESOURCE.matcher(targetExpression.replaceFirst("(?iu)^(?:el|la|un|una|mi|mis) +", "")).find()) {
            method = targetExpression;
        }
        if (method == null) return OTHER;
        String expression = " " + method;
        var change = CHANGE.matcher(expression);
        int start = -1;
        int end = -1;
        String separator = null;
        while (change.find()) {
            if (change.group(1) == null) continue;
            if (start >= 0) return blocked(EDIT_QUESTION, null);
            start = change.start();
            end = change.end();
            separator = change.group(1);
        }
        if (start < 0) {
            var target = selector(method);
            if (target == null && !method.isBlank()) return blocked(SELECTOR, null);
            return editQuestion(new Edicion(target, requested, null));
        }

        Campo indicated = fieldFrom(separator);
        if (requested != Campo.SIN_INDICAR && indicated != Campo.SIN_INDICAR && requested != indicated) {
            return blocked(EDIT_QUESTION, null);
        }
        Campo expected = requested == Campo.SIN_INDICAR ? indicated : requested;
        String targetText = expression.substring(0, start).strip();
        var target = selector(targetText);
        if (target == null && !targetText.isBlank()) return blocked(SELECTOR, null);
        String newValue = expression.substring(end).strip();
        var value = changeValue(newValue, expected);
        if (value == null && !newValue.isBlank()) {
            return blocked(editQuestion(new Edicion(target, expected, null)).message(), null);
        }
        return target != null && value != null ? methodOperation(EDIT, target, value)
                : editQuestion(new Edicion(target, expected, value));
    }

    private static Campo fieldFrom(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("tipo")) return Campo.TIPO;
        return normalized.contains("descripci") ? Campo.DESCRIPCION : Campo.SIN_INDICAR;
    }

    private static Cambio changeValue(String text, Campo field) {
        if (hasUnquotedAction(text)) return null;
        var type = TYPE_AND_REST.matcher(text);
        if (field == Campo.TIPO || (field == Campo.SIN_INDICAR && EXPLICIT_TYPE.matcher(text).find())) {
            return type.matches() && type.group(2) == null ? new Cambio(readType(type.group(1)), null) : null;
        }
        if (field == Campo.SIN_INDICAR && type.matches() && type.group(2) == null) return null;
        String description = registrationDescription(text);
        if (description == null) description = label(text);
        return description == null ? null : new Cambio(null, description);
    }

    private static Decision editQuestion(Edicion draft) {
        if (draft.selector() == null || draft.cambio() != null) return blocked(SELECTOR, draft);
        return blocked(switch (draft.campo()) {
            case TIPO -> "¿Cuál será el nuevo tipo del método de compensación? Indica DINERO, GASOLINA o FAVOR.";
            case DESCRIPCION -> "¿Cuál será la nueva descripción del método?";
            case SIN_INDICAR -> EDIT_QUESTION;
        }, draft);
    }

    private static Decision continueEdit(String text, Edicion draft) {
        if (draft.selector() == null || draft.cambio() != null) {
            var selection = refineSelector(draft.selector(), text);
            if (selection == null) return OTHER;
            return draft.cambio() == null ? editQuestion(new Edicion(selection, draft.campo(), null))
                    : methodOperation(EDIT, selection, draft.cambio());
        }
        var value = changeValue(text, draft.campo());
        return value == null ? editQuestion(draft) : methodOperation(EDIT, draft.selector(), value);
    }

    private static Decision deleteMethod(String subject) {
        if (subject == null) return OTHER;
        String method = ownedSelector(subject);
        var remove = REMOVE_FROM_METHODS.matcher(subject);
        if (method == null && remove.matches()) method = remove.group(1);
        if (method == null) return OTHER;
        var target = selector(method);
        return target != null ? methodOperation(DELETE, target, null)
                : blocked(SELECTOR, method.isBlank() ? new Eliminacion(null) : null);
    }

    private static Selector refineSelector(Selector previous, String text) {
        String named = ownedSelector(text);
        String descriptor = named == null ? text.replaceFirst("(?iu)^(?:el|la) de ", "") : named;
        if (OTHER_RESOURCE.matcher(descriptor).find()) return null;
        var choice = selector(descriptor);
        if (choice == null || previous == null) return choice;
        if (previous.tipo() != null && choice.tipo() != null && previous.tipo() != choice.tipo()) return null;
        if (previous.descripcion() != null && choice.descripcion() != null
                && !choice.descripcion().toLowerCase(Locale.ROOT).contains(previous.descripcion().toLowerCase(Locale.ROOT))) return null;
        var refined = new Selector(choice.tipo() == null ? previous.tipo() : choice.tipo(),
                choice.descripcion() == null ? previous.descripcion() : choice.descripcion());
        return refined.equals(previous) ? null : refined;
    }

    private static Decision methodOperation(String tool, Selector selector, Cambio change) {
        Map<String, Object> arguments = new HashMap<>();
        selector.addTo(arguments);
        if (change != null) change.addTo(arguments);
        return ready(tool, arguments);
    }

    /** Empty means an explicit method without a selector. Null means the text did not name a method. */
    private static String methodText(String subject) {
        if (subject == null) return null;
        var method = METHOD.matcher(subject);
        return !method.matches() ? null : method.group(1) == null ? "" : method.group(1);
    }

    private static String ownedSelector(String subject) {
        String explicitMethod = methodText(subject);
        if (explicitMethod != null || subject == null) return explicitMethod;
        var name = OWNED_NAME.matcher(subject);
        return name.matches() && !OTHER_RESOURCE.matcher(name.group(1)).find() ? name.group(1) : null;
    }

    private static Selector selector(String text) {
        if (hasUnquotedAction(text)) return null;
        var type = TYPE_AND_REST.matcher(text);
        if (type.matches()) {
            if (type.group(2) == null) return new Selector(readType(type.group(1)), null);
            String description = explicitSelectorDescription(type.group(2));
            if (description != null) return new Selector(readType(type.group(1)), description);
        }
        if (EXPLICIT_TYPE.matcher(text).find()) return null;
        String descriptor = text.replaceFirst("(?iu)^de +", "");
        String description = label(descriptor);
        return description == null || description.matches("\\d+") ? null : new Selector(null, description);
    }

    private static String explicitSelectorDescription(String text) {
        var match = DESCRIPTION.matcher(text);
        if (!match.matches()) return null;
        String description = label(match.group(1));
        return description == null || description.matches("\\d+") ? null : description;
    }

    private static String registrationDescription(String text) {
        if (text == null) return null;
        var description = DESCRIPTION.matcher(text);
        if (description.matches()) return label(description.group(1));
        var withDescription = WITH_DESCRIPTION.matcher(text);
        return withDescription.matches() && !ACTION_WORD.matcher(withDescription.group(1)).find()
                ? label(withDescription.group(1)) : null;
    }

    private static String label(String text) {
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("«") && text.endsWith("»")))) {
            String quoted = text.substring(1, text.length() - 1).strip();
            return !quoted.isBlank() && quoted.length() <= 255 && !quoted.matches("(?s).*[\"«»\\r\\n].*")
                    && !AgentConversationStore.containsSecrets(quoted) ? quoted : null;
        }
        return LABEL.matcher(text).matches() && !MULTIPLE.matcher(text).find()
                && !UNSAFE_SELECTOR.matcher(text).find() ? text : null;
    }

    private static boolean hasUnquotedAction(String text) {
        String unquoted = text.replaceAll("\"[^\"]*\"|«[^»]*»", " ");
        return ACTION_WORD.matcher(unquoted).find();
    }

    private static TipoCompensacion readType(String text) {
        return TipoCompensacion.valueOf(text.toUpperCase(Locale.ROOT));
    }

    private static Decision ready(String tool, Map<String, Object> arguments) {
        return new Decision(tool, Map.copyOf(arguments), null, null, false, false);
    }

    private static Decision information(String message) {
        return new Decision(null, null, message, null, true, true);
    }

    private static Decision blocked(String message, Draft draft) {
        return new Decision(null, null, message, draft, true, false);
    }

    private static String clean(String message) {
        String stripped = TERMINAL_PUNCTUATION.matcher(message.strip()).replaceAll("").strip();
        return TRAILING_PLEASE.matcher(WHITESPACE.matcher(stripped).replaceAll(" ")).replaceFirst("");
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
