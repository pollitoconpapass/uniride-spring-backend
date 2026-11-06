package com.uniride.exceptions;

public class DemasiadasSolicitudesException extends RuntimeException {
    public DemasiadasSolicitudesException() {
        super("Demasiados intentos. Espera antes de volver a intentar.");
    }
}
