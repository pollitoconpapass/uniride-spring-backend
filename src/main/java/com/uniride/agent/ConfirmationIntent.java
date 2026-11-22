package com.uniride.agent;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Only complete standalone replies can resolve a server-held proposal. */
enum ConfirmationIntent {
    CONFIRMAR, CANCELAR, AMBIGUA, OTRA;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final String SEPARATOR = "(?: *, *| )";
    private static final Pattern AFFIRMATIVE = Pattern.compile("si(?:" + SEPARATOR + "(?:confirmo|hazlo|procede|gracias))?|"
            + "confirmo(?: la operacion)?|confirmar|hazlo|procede|adelante|de acuerdo|"
            + "esta bien(?:" + SEPARATOR + "hazlo)?|acepto(?: la operacion)?");
    private static final Pattern NEGATIVE = Pattern.compile("no(?:" + SEPARATOR + "gracias| confirmo| lo hagas)?|"
            + "cancelar|cancela la operacion|mejor no|dejalo asi|prefiero cancelar");
    private static final Pattern AMBIGUOUS_START = Pattern.compile("^(?:si\\b|"
            + "confirm(?:o|ar|a|ame)\\b|hazlo\\b|procede\\b|adelante\\b|de acuerdo\\b|"
            + "esta bien\\b|acepto\\b|cancelar\\b|cancela la operacion\\b|prefiero cancelar\\b|"
            + "no *[,;:.!?]|no confirmo\\b|no lo hagas\\b|mejor no\\b|dejalo asi\\b)");

    static ConfirmationIntent classify(String message) {
        String normalized = MARKS.matcher(Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        boolean question = normalized.indexOf('?') >= 0 || normalized.indexOf('¿') >= 0;
        String text = SPACE.matcher(normalized).replaceAll(" ").strip()
                .replaceAll("^[¡¿]+ *| *[.!¡?¿…]+$", "");
        if (AFFIRMATIVE.matcher(text).matches()) return question ? AMBIGUA : CONFIRMAR;
        if (NEGATIVE.matcher(text).matches()) return question ? AMBIGUA : CANCELAR;
        return AMBIGUOUS_START.matcher(text).find() ? AMBIGUA : OTRA;
    }
}
