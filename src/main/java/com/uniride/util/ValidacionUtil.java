package com.uniride.util;

import java.util.regex.Pattern;

public final class ValidacionUtil {

    public static final String CORREO_REGEX =
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.edu([.-]?[A-Za-z0-9]+)*$";

    public static final String CONTRASENA_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^\\w\\s]).{8,}$";

    public static final String TELEFONO_REGEX = "^[+0-9\\s-]{6,15}$";

    private static final Pattern PATRON_CORREO = Pattern.compile(CORREO_REGEX);
    private static final Pattern PATRON_CONTRASENA = Pattern.compile(CONTRASENA_REGEX);
    private static final Pattern PATRON_TELEFONO = Pattern.compile(TELEFONO_REGEX);

    private ValidacionUtil() {
    }

    public static boolean esCorreoValido(String correo) {
        return correo != null && PATRON_CORREO.matcher(correo).matches();
    }

    public static boolean esContrasenaValida(String contrasena) {
        return contrasena != null && PATRON_CONTRASENA.matcher(contrasena).matches();
    }

    public static boolean esTelefonoValido(String telefono) {
        return telefono != null && PATRON_TELEFONO.matcher(telefono).matches();
    }
}
