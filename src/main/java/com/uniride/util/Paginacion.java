package com.uniride.util;

import com.uniride.exceptions.CamposInvalidosException;

public final class Paginacion {
    private Paginacion() {}

    public static void validar(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new CamposInvalidosException("La página debe ser >= 0 y el tamaño debe estar entre 1 y 100");
        }
    }
}
