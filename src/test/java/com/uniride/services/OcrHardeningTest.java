package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.exceptions.*;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class OcrHardeningTest {
    HorarioAcademicoService servicio = new HorarioAcademicoService(null, null, null, null, null, null, "spa+eng", "");
    byte[] pdf(int paginas, PDRectangle medidas) throws Exception {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i=0;i<paginas;i++) doc.addPage(new PDPage(medidas));
            doc.save(out); return out.toByteArray();
        }
    }
    @Test void rechazaVacioTipoFalsoYMalformado() {
        assertThatThrownBy(() -> servicio.extraerTexto(new byte[0])).isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> servicio.extraerTexto("esto es texto".getBytes())).isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> servicio.extraerTexto("%PDF-malformado".getBytes())).isInstanceOf(BusinessException.class);
    }
    @Test void limitaPaginasYGeometriaAntesDeRasterizar() throws Exception {
        assertThatThrownBy(() -> servicio.extraerTexto(pdf(11, PDRectangle.A4))).isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> servicio.extraerTexto(pdf(0, PDRectangle.A4))).isInstanceOf(CamposInvalidosException.class);
        assertThatThrownBy(() -> servicio.extraerTexto(pdf(1, new PDRectangle(100000,100000))))
                .isInstanceOf(CamposInvalidosException.class);
    }
    @Test void pdfTextoValidoSeProcesaSinTesseract() throws Exception {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page);
            try (var contenido = new PDPageContentStream(doc, page)) {
                contenido.beginText(); contenido.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                contenido.newLineAtOffset(10,700); contenido.showText("Lunes 08:00 - 10:00 Software"); contenido.endText();
            }
            doc.save(out);
            java.nio.file.Files.write(java.nio.file.Path.of("target/ocr-text-smoke.pdf"), out.toByteArray());
            assertThat(servicio.extraerTexto(out.toByteArray())).contains("Software");
        }
        // Fixture para verificar OCR nativo en la imagen Docker empaquetada.
        var imagen = new java.awt.image.BufferedImage(1600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var grafico = imagen.createGraphics();
        grafico.setColor(java.awt.Color.WHITE); grafico.fillRect(0,0,1600,400);
        grafico.setColor(java.awt.Color.BLACK); grafico.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,52));
        grafico.drawString("Lunes Software 08:00 - 10:00",60,180); grafico.dispose();
        try (var doc = new PDDocument()) {
            var page = new PDPage(PDRectangle.A4); doc.addPage(page);
            var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,imagen);
            try(var contenido = new PDPageContentStream(doc,page)) { contenido.drawImage(img,20,500,550,138); }
            doc.save("target/ocr-scanned-smoke.pdf");
        } finally { imagen.flush(); }
    }
    @Test void textoAdversarioSeRechazaAntesDeRegexSinBloquearElHilo() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> servicio.parsearCursos("X" + " ".repeat(500000) + "X"))
                    .isInstanceOf(CamposInvalidosException.class);
            assertThatThrownBy(() -> servicio.parsearCursos("X\n".repeat(2001)))
                    .isInstanceOf(CamposInvalidosException.class);
        });
    }
    @Test void lineasSinNombreNoDan500YSeConservanSeparadoresUnicode() {
        assertThat(servicio.parsearCursos("Lunes\n08:00 - 10:00")).isEmpty();
        assertThat(servicio.parsearCursos("Lunes\u2028Software - 1ACC0236\u202808:00 - 10:00")).hasSize(1);
    }
}
