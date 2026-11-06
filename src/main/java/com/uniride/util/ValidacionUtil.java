package com.uniride.util;

import java.util.regex.Pattern;

public final class ValidacionUtil {

    public static final String CORREO_REGEX =
            "^[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+edu(?:\\.[A-Za-z]{2})?$";

    public static final String CONTRASENA_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^\\w\\s]).{8,}$";

    public static final String TELEFONO_REGEX = "^\\+?[0-9][0-9 ()-]{6,24}$";

    public static String normalizarCorreo(String correo) {
        return correo == null ? null : correo.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public static String normalizarTelefono(String telefono) {
        try {
            if (telefono == null || !telefono.matches(TELEFONO_REGEX)) throw new IllegalArgumentException();
            var util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance();
            var numero = util.parse(telefono, "PE");
            if (!util.isValidNumber(numero) || numero.hasExtension()) throw new IllegalArgumentException();
            return util.format(numero, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (Exception e) {
            throw new com.uniride.exceptions.CamposInvalidosException("El teléfono no tiene un formato válido");
        }
    }

    private static final Pattern PATRON_CORREO = Pattern.compile(CORREO_REGEX);
    private static final Pattern PATRON_CONTRASENA = Pattern.compile(CONTRASENA_REGEX);
    private static final Pattern PATRON_TELEFONO = Pattern.compile(TELEFONO_REGEX);

    private ValidacionUtil() {
    }

    public static boolean esCorreoValido(String correo) {
        return correo != null && PATRON_CORREO.matcher(normalizarCorreo(correo)).matches();
    }

    public static boolean esContrasenaValida(String contrasena) {
        return contrasena != null && PATRON_CONTRASENA.matcher(contrasena).matches();
    }

    public static boolean esTelefonoValido(String telefono) {
        try { normalizarTelefono(telefono); return true; }
        catch (com.uniride.exceptions.CamposInvalidosException e) { return false; }
    }
}
