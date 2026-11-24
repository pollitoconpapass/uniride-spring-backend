package com.uniride.exceptions;

public class SalidaConPocaAnticipacionException extends BusinessException {
    public SalidaConPocaAnticipacionException() {
        super("La salida debe tener al menos 12 horas de anticipación");
    }
}
