package com.uniride.security;

import com.uniride.exceptions.DemasiadasSolicitudesException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Admisión local acotada. OTP/cooldown se serializan además en PostgreSQL. */
@Component
public class LimiteAutenticacion {
    @Autowired private Clock clock = Clock.systemUTC();
    @Value("${uniride.auth.limite-cuenta:30}") private int limiteCuenta = 30;
    @Value("${uniride.auth.limite-ip:100}") private int limiteIp = 100;
    @Value("${uniride.auth.ventana-segundos:900}") private long ventanaSegundos = 900;
    private final Map<String, Contador> contadores = new HashMap<>();

    public void cuenta(String correo) { consumir("cuenta:" + correo, limiteCuenta); }
    public void ip(String ip) { consumir("ip:" + ip, limiteIp); }

    private synchronized void consumir(String clave, int maximo) {
        Instant ahora = clock.instant();
        contadores.values().removeIf(c -> !c.hasta.isAfter(ahora));
        Contador contador = contadores.get(clave);
        // No expulsar claves activas: evita eludir presupuestos enviando claves nuevas.
        if (contador == null) {
            if (contadores.size() >= 10_000) throw new DemasiadasSolicitudesException();
            contador = new Contador(ahora.plus(Duration.ofSeconds(ventanaSegundos)));
            contadores.put(clave, contador);
        }
        if (++contador.intentos > maximo) throw new DemasiadasSolicitudesException();
    }

    private static class Contador {
        final Instant hasta;
        int intentos;
        Contador(Instant hasta) { this.hasta = hasta; }
    }
}
