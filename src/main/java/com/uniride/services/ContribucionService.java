package com.uniride.services;

import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContribucionService {

    private static final double TARIFA_BASE = 8.0;
    private static final double RECARGO_HORA_PICO = 1.5;
    private static final double PRECIO_LITRO_GASOLINA = 5.4;
    private static final LocalTime INICIO_PICO_MANANA = LocalTime.of(6, 0);
    private static final LocalTime FIN_PICO_MANANA = LocalTime.of(10, 0);
    private static final LocalTime INICIO_PICO_TARDE = LocalTime.of(17, 0);
    private static final LocalTime FIN_PICO_TARDE = LocalTime.of(21, 0);

    private final ViajeRepository viajeRepository;

    public ContribucionService(ViajeRepository viajeRepository) {
        this.viajeRepository = viajeRepository;
    }

    @Transactional(readOnly = true)
    public SugerenciaContribucionRespuesta sugerencia(Long viajeId, Integer pasajeros) {
        Viaje viaje = viajeRepository.findById(viajeId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException("El viaje no está disponible para calcular una sugerencia; "
                    + "estado actual: " + viaje.getEstado());
        }

        int pasajerosEnViaje = viaje.getPasajeros();
        if (pasajerosEnViaje > 0) {
            pasajeros = pasajerosEnViaje;
        }
        if (pasajeros == null) {
            throw new CamposInvalidosException(
                    "El viaje aún no tiene pasajeros aceptados; indica la cantidad de pasajeros "
                            + "para calcular la sugerencia");
        }
        if (pasajeros < 1) {
            throw new CamposInvalidosException("La cantidad de pasajeros debe ser al menos 1");
        }

        boolean esHoraPico = esHoraPico(viaje.getHora());
        double costoTotal = redondear(TARIFA_BASE * (esHoraPico ? RECARGO_HORA_PICO : 1.0));
        double aporte = redondear(costoTotal / pasajeros);
        double litros = redondear(aporte / PRECIO_LITRO_GASOLINA);

        return new SugerenciaContribucionRespuesta(
                viaje.getId(),
                viaje.getRuta().getOrigen(),
                viaje.getRuta().getDestino(),
                viaje.getDia(),
                viaje.getFecha(),
                viaje.getHora(),
                pasajeros,
                esHoraPico,
                costoTotal,
                aporte,
                litros,
                "Un favor acordado con el conductor de valor equivalente al aporte estimado",
                "Estimación referencial basada en la hora del viaje y la cantidad de pasajeros; "
                        + "el monto final puede variar según la ruta y el acuerdo entre conductor y pasajeros.");
    }

    private boolean esHoraPico(LocalTime hora) {
        boolean manana = !hora.isBefore(INICIO_PICO_MANANA) && hora.isBefore(FIN_PICO_MANANA);
        boolean tarde = !hora.isBefore(INICIO_PICO_TARDE) && hora.isBefore(FIN_PICO_TARDE);
        return manana || tarde;
    }

    private double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }
}
