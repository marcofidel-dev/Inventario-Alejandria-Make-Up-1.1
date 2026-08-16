package com.alejandriamakeup.pos.ventas.recibo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alejandriamakeup.pos.configuracion.ServicioConfiguracion;
import com.alejandriamakeup.pos.ventas.GeneradorComprobante;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaItem;

/**
 * El comprobante en PDF, en la carpeta de datos de la aplicación.
 *
 * <p>Es la implementación de hoy de {@link GeneradorComprobante}. La interfaz existe
 * para que mañana un adaptador de facturación electrónica DIAN entre por esta misma
 * costura sin tocar el punto de venta.
 *
 * <p><strong>Ancho fijo, alto variable.</strong> Se cuentan las líneas primero y con
 * ese número se construye el {@link PDRectangle}. Un tamaño predefinido dejaría media
 * página en blanco en una venta de dos líneas y cortaría una de veinte.
 *
 * <p><strong>Courier de las 14 base, sin embeber.</strong> No hace falta cargar ningún
 * archivo de fuente ni engordar el PDF, y cualquier visor la tiene.
 * {@code WinAnsiEncoding} cubre tildes y eñe;
 * {@link ReciboTexto#limpiar(String)} se encarga de que no llegue nada que no cubra.
 */
@Component
public class PdfReciboLocal implements GeneradorComprobante {

    private static final Logger log = LoggerFactory.getLogger(PdfReciboLocal.class);

    /** 80mm en puntos PostScript: 80 / 25,4 * 72 = 226,77. */
    private static final float ANCHO_PT = 227f;

    private static final float TAMANO_FUENTE = 8f;

    /** Courier a 8pt: 0,6 em de ancho por carácter = 4,8 pt. De ahí salen las 42. */
    private static final float ANCHO_CARACTER_PT = TAMANO_FUENTE * 0.6f;

    private static final float INTERLINEADO_PT = 9.6f;
    private static final float MARGEN_VERTICAL_PT = 20f;

    /** Lo que sobra a los lados del bloque de 42 columnas, repartido en dos. */
    private static final float MARGEN_HORIZONTAL_PT =
            (ANCHO_PT - ReciboTexto.ANCHO * ANCHO_CARACTER_PT) / 2;

    private static final DateTimeFormatter CARPETA_ANO = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter CARPETA_MES = DateTimeFormatter.ofPattern("MM");

    private final ServicioConfiguracion servicioConfiguracion;
    private final Path raiz;

    public PdfReciboLocal(ServicioConfiguracion servicioConfiguracion,
                          @Value("${app.paths.raiz}") String raiz) {
        this.servicioConfiguracion = servicioConfiguracion;
        this.raiz = Path.of(raiz);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Particionado por año y mes: {@code recibos/2026/08/V-000123.pdf}. Una sola
     * carpeta con los recibos de cinco años son decenas de miles de archivos, y eso
     * hace lento hasta abrirla en el explorador de Windows.
     */
    @Override
    public String generar(Venta venta, List<VentaItem> items) {
        List<String> lineas = ReciboTexto.de(venta, items, servicioConfiguracion.tienda());

        String relativa = "recibos/" + CARPETA_ANO.format(venta.getFecha()) + "/"
                + CARPETA_MES.format(venta.getFecha()) + "/" + venta.getConsecutivo() + ".pdf";
        Path destino = raiz.resolve(relativa);

        try {
            Files.createDirectories(destino.getParent());
            escribir(lineas, destino);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el recibo en " + destino, e);
        }

        log.info("Recibo de la venta {} escrito en {} ({} líneas)",
                venta.getConsecutivo(), relativa, lineas.size());
        return relativa;
    }

    private void escribir(List<String> lineas, Path destino) throws IOException {
        float alto = lineas.size() * INTERLINEADO_PT + MARGEN_VERTICAL_PT * 2;

        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage(new PDRectangle(ANCHO_PT, alto));
            documento.addPage(pagina);

            // PDFBox 3: la fuente se construye. El campo estático PDType1Font.COURIER
            // que usan los tutoriales de 2.x ya no existe.
            PDType1Font courier = new PDType1Font(Standard14Fonts.FontName.COURIER);

            try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                contenido.beginText();
                contenido.setFont(courier, TAMANO_FUENTE);
                contenido.setLeading(INTERLINEADO_PT);
                contenido.newLineAtOffset(MARGEN_HORIZONTAL_PT, alto - MARGEN_VERTICAL_PT);
                for (String linea : lineas) {
                    contenido.showText(linea);
                    contenido.newLine();
                }
                contenido.endText();
            }

            documento.save(destino.toFile());
        }
    }
}
