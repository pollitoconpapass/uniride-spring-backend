package com.uniride.agent;

import com.uniride.enums.TipoCompensacion;
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
    private static final Decision OTHER = new Decision(null, null, null, null, false);

    private static final Pattern METHOD = pattern("^(?:(?:mi|el|un|este|ese) )?(?:nuevo )?m[eé]todo(?: de compensaci[oó]n)?(?: (.*))?$");
    private static final Pattern OWNED_NAME = pattern("^mi (.+)$");
    private static final Pattern OTHER_RESOURCE = pattern("^(?:cuenta|perfil|ruta|viaje|solicitud|cupo|reserva|"
            + "sesi[oó]n|ubicaci[oó]n|notificaciones?|alertas?|historial|modo|suscripci[oó]n|"
            + "opci[oó]n|servicio|conversaci[oó]n|contrase[nñ]a|c[oó]digo)\\b");
    private static final Pattern TYPE_AND_REST = pattern("^(?:(?:de tipo|tipo|de) )?(" + TYPE + ")(?:(?:, *| +)(.+))?$");
    private static final Pattern CHANGE_STATE = pattern("^" + REQUEST
            + "(activa(?:r)?|desactiva(?:r)?)(?: nuevamente| de nuevo| otra vez)?(?: (.+))?$");
    private static final Pattern REGISTER_REQUEST = pattern("^" + REQUEST
            + "(?:reg[ií]stra(?:r|me)?|agrega(?:r|me)?|a[nñ]ade(?:r)?)(?: (.+))?$");
    private static final Pattern ADD_PREFERENCE = pattern("^prefiero (?:compensar con )?(" + TYPE
            + ")(?:, *| +)(?:agr[eé]galo|reg[ií]stralo|a[nñ][aá]delo)(?: (.+))?$");
    private static final Pattern EDIT_REQUEST = pattern("^" + REQUEST + "(?:cambia(?:r)?|edita(?:r)?|modifica(?:r)?|actualiza(?:r)?) (.+)$");
    private static final Pattern DELETE_REQUEST = pattern("^" + REQUEST + "(?:elimina(?:r)?|borra(?:r)?|quita(?:r)?) (.+)$");
    private static final Pattern REMOVE_FROM_METHODS = pattern("^(.+) de mis m[eé]todos(?: de compensaci[oó]n)?$");
    private static final Pattern FIELD = pattern("^((?:la )?descripci[oó]n|(?:el )?tipo) de (.+)$");
    private static final Pattern CHANGE = pattern(" (?:a|al tipo|por|con (?:tipo|descripci[oó]n|nueva descripci[oó]n)) (.+)$");
    private static final Pattern DESCRIPTION = pattern("^(?:(?:con )?(?:una )?descripci[oó]n(?: es| de)? *:? *|(?:llamad[oa]|con nombre) )(.+)$");
    private static final Pattern WITH_DESCRIPTION = pattern("^con (?!tipo\\b|descripci[oó]n\\b)(.+)$");
    private static final Pattern INFORMATION = pattern("^(?:c[oó]mo|qu[eé] pasa si|expl[ií]ca(?:me)?|puedo|podr[ií]a|debo|es posible)\\b");
    private static final Pattern NEGATION = pattern("^(?:por favor,? )?(?:no|nunca|jam[aá]s)\\b");
    private static final Pattern AFFIRMATION = pattern("^(?:s[ií](?:,? (?:confirmo|hazlo|gracias|procede))?|confirmo(?: la operaci[oó]n)?|"
            + "est[aá] bien(?:,? hazlo)?|procede|acepto(?: la operaci[oó]n)?|de acuerdo|adelante|hazlo)$");
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

    private CompensationIntent() { }

    record Registro(TipoCompensacion tipo, String descripcion) { }

    record Decision(String allowedTool, Map<String, Object> recoveryArgs, String message,
                    Registro draft, boolean blockPreparations) { }

    static Decision classify(String message, Registro draft) {
        String text = clean(message);
        if (AgentConversationStore.containsSecrets(message) || isAffirmation(message) || NEGATION.matcher(text).find()) {
            return blocked(null, null);
        }
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
            return changeState(state.group(1), state.group(2));
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
        if (delete.matches()) {
            String method = ownedSelector(delete.group(1));
            var remove = REMOVE_FROM_METHODS.matcher(delete.group(1));
            if (method == null && remove.matches()) method = remove.group(1);
            if (method == null) return OTHER;
            return selector(method) != null
                    ? delegated(DELETE, "Indica de nuevo el tipo o la descripción exacta del método que quieres eliminar para preparar la confirmación.")
                    : blocked(SELECTOR, null);
        }
        return draft == null ? OTHER : continueRegistration(message, text, draft);
    }

    static boolean isAffirmation(String message) {
        return !message.strip().endsWith("?") && AFFIRMATION.matcher(clean(message)).matches();
    }

    static boolean isTripRequest(String message) {
        return message != null && TRIP.matcher(message).find();
    }

    private static Decision changeState(String verb, String subject) {
        String method = ownedSelector(subject);
        if (method == null) {
            if (subject == null) return blocked(SELECTOR, null);
            String type = subject.replaceFirst("(?iu)^(?:mi|el) ", "");
            if (!TYPE_AND_REST.matcher(type).matches()) return OTHER;
            method = type;
        }
        var selector = selector(method);
        if (selector == null) return blocked(SELECTOR, null);
        boolean active = verb.toLowerCase(Locale.ROOT).startsWith("activa");
        return new Decision(STATE, selector.tipo() == null
                ? Map.of("descripcionActual", selector.descripcion(), "activo", active)
                : Map.of("tipoActual", selector.tipo(), "activo", active), null, null, false);
    }

    private static Decision registerMethod(String subject) {
        if (subject == null) return OTHER;
        String method = methodText(subject);
        boolean explicitMethod = method != null;
        var type = TYPE_AND_REST.matcher(explicitMethod ? method : subject);
        if (type.matches()) return registration(readType(type.group(1)), registrationDescription(type.group(2)));
        return explicitMethod ? registration(null, registrationDescription(method)) : OTHER;
    }

    private static Decision continueRegistration(String original, String text, Registro draft) {
        if (original.contains("?") || original.contains("¿") || NEW_TOPIC.matcher(text).find() || GREETING.matcher(text).matches()
                || ACTION_WORD.matcher(text).find() || isTripRequest(text) || !AgentReportIntent.classify(text).isEmpty()) return OTHER;
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
        if (type == null) return blocked(TYPE_QUESTION, new Registro(null, description));
        if (description == null) {
            return blocked("¿Qué descripción quieres asignar a tu nuevo método " + type + "?", new Registro(type, null));
        }
        return new Decision(REGISTER, Map.of("tipo", type, "descripcion", description), null, null, false);
    }

    private static Decision editMethod(String subject) {
        var field = FIELD.matcher(subject);
        boolean explicitField = field.matches();
        String method = ownedSelector(explicitField ? field.group(2) : subject);
        if (method == null) return OTHER;
        var change = CHANGE.matcher(method);
        if (!change.find()) {
            if (selector(method) == null) return blocked(SELECTOR, null);
            if (!explicitField) return blocked(EDIT_QUESTION, null);
            return blocked(field.group(1).toLowerCase(Locale.ROOT).endsWith("tipo")
                    ? "¿Cuál será el nuevo tipo del método de compensación?" : "¿Cuál será la nueva descripción del método?", null);
        }
        if (selector(method.substring(0, change.start())) == null || label(change.group(1)) == null) {
            return blocked(EDIT_QUESTION, null);
        }
        return delegated(EDIT, "Indica de nuevo el método y el nuevo tipo o la nueva descripción para preparar el cambio.");
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

    private static Registro selector(String text) {
        var type = TYPE_AND_REST.matcher(text);
        if (type.matches() && type.group(2) == null) return new Registro(readType(type.group(1)), null);
        String descriptor = text.replaceFirst("(?iu)^de +", "");
        String description = label(descriptor);
        return description == null || descriptor.matches("\\d+") ? null : new Registro(null, description);
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
            return !quoted.isBlank() && quoted.length() <= 255 && !AgentConversationStore.containsSecrets(quoted) ? quoted : null;
        }
        return LABEL.matcher(text).matches() && !MULTIPLE.matcher(text).find()
                && !UNSAFE_SELECTOR.matcher(text).find() ? text : null;
    }

    private static TipoCompensacion readType(String text) {
        return TipoCompensacion.valueOf(text.toUpperCase(Locale.ROOT));
    }

    private static Decision delegated(String tool, String message) {
        return new Decision(tool, null, message, null, false);
    }

    private static Decision blocked(String message, Registro draft) {
        return new Decision(null, null, message, draft, true);
    }

    private static String clean(String message) {
        String stripped = TERMINAL_PUNCTUATION.matcher(message.strip()).replaceAll("").strip();
        return TRAILING_PLEASE.matcher(WHITESPACE.matcher(stripped).replaceAll(" ")).replaceFirst("");
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
