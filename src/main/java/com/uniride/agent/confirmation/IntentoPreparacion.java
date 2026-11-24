package com.uniride.agent.confirmation;

/** Tracks crossing the persistence boundary on the synchronous tool call thread. */
public final class IntentoPreparacion implements AutoCloseable {
    private static final ThreadLocal<IntentoPreparacion> ACTUAL = new ThreadLocal<>();
    private boolean iniciada;

    private IntentoPreparacion() { }

    public static IntentoPreparacion observar() {
        var intento = new IntentoPreparacion();
        ACTUAL.set(intento);
        return intento;
    }

    static void marcarPersistencia() {
        var intento = ACTUAL.get();
        if (intento != null) intento.iniciada = true;
    }

    public boolean iniciada() { return iniciada; }

    @Override public void close() { ACTUAL.remove(); }
}
