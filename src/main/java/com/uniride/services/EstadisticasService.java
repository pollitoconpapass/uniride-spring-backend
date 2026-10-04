package com.uniride.services;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import com.uniride.entities.Penalidad;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EstadisticasService {

    private static final long VIAJES_MINIMOS = 5;
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<String> DIAS_SEMANA = List.of(
            "Lunes", "Martes", "Miercoles", "Jueves", "Viernes", "Sabado", "Domingo");

    private final ViajeRepository viajeRepository;
    private final SolicitudRepository solicitudRepository;
    private final PenalidadRepository penalidadRepository;
    private final UsuarioService usuarioService;

    public EstadisticasService(ViajeRepository viajeRepository,
            SolicitudRepository solicitudRepository, PenalidadRepository penalidadRepository,
            UsuarioService usuarioService) {
        this.viajeRepository = viajeRepository;
        this.solicitudRepository = solicitudRepository;
        this.penalidadRepository = penalidadRepository;
        this.usuarioService = usuarioService;
    }

    @Transactional(readOnly = true)
    public EstadisticasRespuesta resumen() {
        Usuario usuario = usuarioService.usuarioActual();
        Long id = usuario.getId();

        long viajesComoConductor = viajeRepository.countByRutaConductorIdAndEstado(
                id, EstadoViaje.COMPLETADO);
        long viajesComoPasajero = solicitudRepository.contarRealizadosComoPasajero(
                id, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);
        long canceladosComoConductor = viajeRepository.countByRutaConductorIdAndEstado(
                id, EstadoViaje.CANCELADO);
        long canceladosComoPasajero = solicitudRepository.contarViajesCanceladosComoPasajero(
                id, EstadoViaje.CANCELADO);
        long penalidades = penalidadRepository.countByUsuarioId(id);

        return new EstadisticasRespuesta(
                viajesComoConductor,
                viajesComoPasajero,
                canceladosComoConductor + canceladosComoPasajero,
                penalidades);
    }

    @Transactional(readOnly = true)
    public AnalisisSemanalRespuesta analisisSemanal() {
        Usuario usuario = usuarioService.usuarioActual();
        Long id = usuario.getId();

        long realizados = viajeRepository.countByRutaConductorIdAndEstado(id, EstadoViaje.COMPLETADO)
                + solicitudRepository.contarRealizadosComoPasajero(
                        id, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO);
        long canceladosPorMi = penalidadRepository.countByUsuarioIdAndViajeIsNotNull(id);
        long canceladosPorTerceros = solicitudRepository.contarViajesCanceladosPorTerceros(
                id, EstadoViaje.CANCELADO);

        if (realizados < VIAJES_MINIMOS) {
            return new AnalisisSemanalRespuesta(
                    false,
                    "Tienes " + realizados
                            + " viaje(s) completado(s); necesitas al menos "
                            + VIAJES_MINIMOS + " para generar el análisis semanal.",
                    List.of(),
                    canceladosPorMi,
                    canceladosPorTerceros);
        }

        Map<String, Long> conteo = new HashMap<>();
        for (Object[] fila : viajeRepository.frecuenciaPorDiaComoConductor(id, EstadoViaje.COMPLETADO)) {
            conteo.merge((String) fila[0], ((Number) fila[1]).longValue(), Long::sum);
        }
        for (Object[] fila : solicitudRepository.frecuenciaPorDiaComoPasajero(
                id, EstadoSolicitud.ACEPTADA, EstadoViaje.COMPLETADO)) {
            conteo.merge((String) fila[0], ((Number) fila[1]).longValue(), Long::sum);
        }

        List<DiaCantidadRespuesta> porDia = DIAS_SEMANA.stream()
                .map(dia -> new DiaCantidadRespuesta(dia, conteo.getOrDefault(dia, 0L)))
                .toList();

        return new AnalisisSemanalRespuesta(
                true,
                null,
                porDia,
                canceladosPorMi,
                canceladosPorTerceros);
    }

    @Transactional(readOnly = true)
    public RankingRutasRespuesta rankingRutas() {
        Usuario usuario = usuarioService.usuarioActual();
        Long id = usuario.getId();

        long completados = viajeRepository.countByRutaConductorIdAndEstado(
                id, EstadoViaje.COMPLETADO);
        if (completados < VIAJES_MINIMOS) {
            return new RankingRutasRespuesta(
                    false,
                    "Tienes " + completados
                            + " viaje(s) completado(s) como conductor; necesitas al menos "
                            + VIAJES_MINIMOS + " para generar el ranking de rutas.",
                    List.of());
        }

        Map<String, RutaFrecuenteRespuesta> porCombo = new LinkedHashMap<>();
        for (Object[] fila : viajeRepository.rankingRutasComoConductor(id, EstadoViaje.COMPLETADO)) {
            String origen = (String) fila[0];
            String destino = (String) fila[1];
            long cantidad = ((Number) fila[2]).longValue();
            porCombo.put(clave(origen, destino),
                    new RutaFrecuenteRespuesta(origen, destino, cantidad, 0));
        }

        for (Object[] fila : viajeRepository.penalidadesPorRutaComoConductor(id)) {
            String claveCombo = clave((String) fila[0], (String) fila[1]);
            RutaFrecuenteRespuesta existente = porCombo.get(claveCombo);
            if (existente != null) {
                porCombo.put(claveCombo, new RutaFrecuenteRespuesta(
                        existente.origen(), existente.destino(), existente.cantidad(),
                        ((Number) fila[2]).longValue()));
            }
        }

        return new RankingRutasRespuesta(
                true,
                null,
                new ArrayList<>(porCombo.values()));
    }

    @Transactional(readOnly = true)
    public byte[] exportarHistorialPDF(LocalDate desde, LocalDate hasta, String tipo) {
        Usuario usuario = usuarioService.usuarioActual();

        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new CamposInvalidosException(
                    "La fecha 'desde' no puede ser posterior a la fecha 'hasta'");
        }
        String tipoLimpio = tipo == null ? "todos" : tipo.trim().toLowerCase();
        if (!tipoLimpio.equals("todos") && !tipoLimpio.equals("conductor")
                && !tipoLimpio.equals("pasajero")) {
            throw new CamposInvalidosException(
                    "El parámetro tipo debe ser 'todos', 'conductor' o 'pasajero'");
        }

        Map<Long, String> penalidades = penalidadesPorViaje(usuario.getId());
        List<FilaHistorial> filas = new ArrayList<>();
        LocalDate hoy = LocalDate.now(ZoneId.systemDefault());

        if (!tipoLimpio.equals("pasajero")) {
            List<Viaje> viajes = viajeRepository.historialComoConductor(
                    usuario.getId(), EstadoViaje.PROGRAMADO, hoy);
            for (Viaje viaje : viajes) {
                if (!incluirEnRango(viaje.getFecha(), desde, hasta)) {
                    continue;
                }
                filas.add(new FilaHistorial(
                        viaje.getFecha().format(FORMATO_FECHA),
                        viaje.getDia(),
                        viaje.getHora().toString(),
                        viaje.getRuta().getOrigen() + " - " + viaje.getRuta().getDestino(),
                        "Conductor",
                        viaje.getEstado().name(),
                        penalidades.getOrDefault(viaje.getId(), "-")));
            }
        }

        if (!tipoLimpio.equals("conductor")) {
            List<Solicitud> solicitudes = solicitudRepository.historialComoPasajero(
                    usuario.getId(), List.of(EstadoSolicitud.ACEPTADA, EstadoSolicitud.CANCELADA));
            for (Solicitud solicitud : solicitudes) {
                Viaje viaje = solicitud.getViaje();
                if (viaje.getEstado() == EstadoViaje.PROGRAMADO && viaje.getFecha().isAfter(hoy)) {
                    continue;
                }
                if (!incluirEnRango(viaje.getFecha(), desde, hasta)) {
                    continue;
                }
                String estado = viaje.getEstado().name();
                if (solicitud.getEstado() == EstadoSolicitud.CANCELADA
                        && viaje.getEstado() != EstadoViaje.CANCELADO) {
                    estado += " (solicitud cancelada)";
                }
                filas.add(new FilaHistorial(
                        viaje.getFecha().format(FORMATO_FECHA),
                        viaje.getDia(),
                        viaje.getHora().toString(),
                        viaje.getRuta().getOrigen() + " - " + viaje.getRuta().getDestino(),
                        "Pasajero",
                        estado,
                        penalidades.getOrDefault(viaje.getId(), "-")));
            }
        }

        if (filas.isEmpty()) {
            throw new BusinessException("No hay historial para exportar");
        }

        return generarPDF(usuario, filas, describirRango(desde, hasta, tipoLimpio));
    }

    private Map<Long, String> penalidadesPorViaje(Long usuarioId) {
        Map<Long, String> porViaje = new HashMap<>();
        for (Penalidad penalidad : penalidadRepository.findByUsuarioId(usuarioId)) {
            if (penalidad.getViaje() == null) {
                continue;
            }
            porViaje.merge(penalidad.getViaje().getId(),
                    penalidad.getTipo() + ": " + penalidad.getMotivo(),
                    (a, b) -> a + " | " + b);
        }
        return porViaje;
    }

    private boolean incluirEnRango(LocalDate fecha, LocalDate desde, LocalDate hasta) {
        if (desde != null && fecha.isBefore(desde)) {
            return false;
        }
        if (hasta != null && fecha.isAfter(hasta)) {
            return false;
        }
        return true;
    }

    private String describirRango(LocalDate desde, LocalDate hasta, String tipo) {
        String rango;
        if (desde == null && hasta == null) {
            rango = "Todo el historial";
        } else if (desde == null) {
            rango = "Hasta " + hasta.format(FORMATO_FECHA);
        } else if (hasta == null) {
            rango = "Desde " + desde.format(FORMATO_FECHA);
        } else {
            rango = "Del " + desde.format(FORMATO_FECHA) + " al " + hasta.format(FORMATO_FECHA);
        }
        return rango + " | Tipo: " + tipo;
    }

    private String clave(String origen, String destino) {
        return origen + "→" + destino;
    }

    private byte[] generarPDF(Usuario usuario, List<FilaHistorial> filas, String rango) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            Document documento = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
            PdfWriter.getInstance(documento, salida);
            documento.open();

            Font fuenteTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16,
                    Color.DARK_GRAY);
            Font fuenteSub = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY);
            Font fuenteCelda = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font fuenteCabecera = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

            documento.add(new Paragraph("UniRide - Historial de viajes", fuenteTitulo));
            documento.add(new Paragraph("Estudiante: " + usuario.getNombre() + " "
                    + usuario.getApellidos() + " (" + usuario.getCorreoInstitucional() + ")",
                    fuenteSub));
            documento.add(new Paragraph(rango + " | Exportado el "
                    + LocalDate.now(ZoneId.systemDefault()).format(FORMATO_FECHA), fuenteSub));
            documento.add(new Paragraph(" ", fuenteSub));

            PdfPTable tabla = new PdfPTable(new float[]{9, 9, 7, 31, 9, 13, 22});
            tabla.setWidthPercentage(100);
            for (String cabecera : List.of("Fecha", "Día", "Hora", "Ruta", "Rol", "Estado",
                    "Penalidad")) {
                PdfPCell celda = new PdfPCell(new Phrase(cabecera, fuenteCabecera));
                celda.setBackgroundColor(new Color(230, 230, 230));
                celda.setPadding(5);
                tabla.addCell(celda);
            }
            for (FilaHistorial fila : filas) {
                tabla.addCell(celdaTabla(fila.fecha(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.dia(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.hora(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.ruta(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.rol(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.estado(), fuenteCelda));
                tabla.addCell(celdaTabla(fila.penalidad(), fuenteCelda));
            }

            documento.add(tabla);
            documento.close();
            return salida.toByteArray();
        } catch (DocumentException e) {
            throw new BusinessException("No se pudo generar el PDF del historial");
        }
    }

    private PdfPCell celdaTabla(String texto, Font fuente) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, fuente));
        celda.setPadding(4);
        return celda;
    }

    private record FilaHistorial(String fecha, String dia, String hora, String ruta,
            String rol, String estado, String penalidad) {
    }
}
