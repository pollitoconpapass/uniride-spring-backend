package com.uniride.ocr;

import com.uniride.services.HorarioAcademicoService;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Proceso desechable: el padre aplica tiempo, concurrencia y heap máximos. */
public final class PdfWorker {
    public static void main(String[] args) {
        try {
            System.setProperty("uniride.ocr.tempdir", Path.of(args[0]).toAbsolutePath().getParent().toString());
            var servicio = new HorarioAcademicoService(null,null,null,null,null,null,args[2],args[3]);
            String texto = servicio.extraerTextoWorker(Files.readAllBytes(Path.of(args[0])));
            Files.writeString(Path.of(args[1]), texto, StandardCharsets.UTF_8);
        } catch (com.uniride.exceptions.CamposInvalidosException e) {
            System.exit(3);
        } catch (Exception | LinkageError e) {
            // No reproducir detalles del parser, rutas ni contenido del archivo.
            System.exit(2);
        }
    }
}
