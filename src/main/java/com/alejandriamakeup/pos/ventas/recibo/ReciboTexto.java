package com.alejandriamakeup.pos.ventas.recibo;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.alejandriamakeup.pos.configuracion.dto.DatosTiendaDto;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaItem;

/**
 * El recibo como lista de líneas de 42 caracteres. Sin PDF, sin archivos, sin Spring.
 *
 * <p><strong>Toda la parte delicada del comprobante es texto</strong> —la envoltura de
 * los nombres largos, la alineación de los importes, qué línea aparece y cuál no— y
 * aquí se puede probar entera sin escribir un solo byte en disco.
 * {@link PdfReciboLocal} solo pinta lo que salga de aquí.
 *
 * <p><strong>42 columnas.</strong> El papel es de 80mm ≈ 227 pt, el área útil ≈ 204 pt
 * y Courier a 8pt mide 4,8 pt por carácter: 204 / 4,8 = 42,5. Con 43 la última columna
 * se sale del área imprimible y el corte no se ve en pantalla, solo en papel.
 *
 * <p><strong>Lo que se imprime sale de la venta, no del catálogo.</strong>
 * {@code descripcion_congelada} y {@code precio_unitario_congelado} de
 * {@code venta_item}, sin un solo join contra producto o variante. Cambiar el nombre
 * de un producto mañana no puede cambiar el comprobante de lo que se entregó hoy.
 *
 * <p><strong>Nunca costos ni márgenes</strong>, para ningún rol: el recibo se le
 * entrega a la clienta. Y nunca la palabra FACTURA salvo en el pie que aclara que no
 * lo es — este documento no está validado por la DIAN.
 */
public final class ReciboTexto {

    /** Ver la explicación de las 42 columnas en la documentación de la clase. */
    public static final int ANCHO = 42;

    /**
     * Una última línea con una sola palabra de hasta este largo se considera huérfana
     * y se le baja una palabra de la línea anterior. Cinco caracteres porque las
     * huérfanas que de verdad afean son las cortas —"mate", "5 ml", "Rojo"—: una
     * palabra larga sola llena la línea y se lee como parte del bloque.
     */
    private static final int HUERFANA_MAXIMA = 5;

    private static final String DOBLE = "=".repeat(ANCHO);
    private static final String SIMPLE = "-".repeat(ANCHO);

    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * La única mención permitida de la palabra. No sale de la configuración y no hay
     * forma de quitarla desde ninguna pantalla: es lo que distingue este papel de un
     * documento fiscal.
     */
    private static final String ACLARACION_LEGAL = "Este documento no es una factura";

    private ReciboTexto() {
    }

    // ------------------------------------------------------------------ recibo

    public static List<String> de(Venta venta, List<VentaItem> items, DatosTiendaDto tienda) {
        List<String> lineas = new ArrayList<>();

        lineas.add(DOBLE);
        encabezado(lineas, tienda);
        lineas.add(DOBLE);

        // "RECIBO DE VENTA", nunca "FACTURA".
        lineas.add(alinear("RECIBO DE VENTA", "No. " + venta.getConsecutivo()));
        lineas.add(alinear(FECHA.format(venta.getFecha()),
                "Atendió: " + venta.getUsuario().getNombre()));
        lineas.add(SIMPLE);

        for (VentaItem item : items) {
            lineas.addAll(envolver(item.getDescripcionCongelada()));
            // La línea de cantidad va aparte y NUNCA se envuelve: es la que se lee en
            // diagonal para comprobar una cuenta, y partida deja de servir para eso.
            lineas.add(alinear(
                    "  " + item.getCantidad() + " x " + pesos(item.getPrecioUnitarioCongelado()),
                    pesos(item.getSubtotal())));
        }

        lineas.add(SIMPLE);
        lineas.add(alinear("SUBTOTAL", pesos(venta.getSubtotal())));
        // Sin descuento no hay línea de descuento: un "DESCUENTO 0" invita a preguntar
        // por un descuento que nadie aplicó.
        if (venta.getDescuento() != 0) {
            lineas.add(alinear("DESCUENTO", "-" + pesos(venta.getDescuento())));
        }
        lineas.add(alinear("TOTAL", pesos(venta.getTotal())));

        lineas.add(SIMPLE);
        lineas.addAll(pago(venta));

        lineas.add(DOBLE);
        centrarEn(lineas, tienda == null ? null : tienda.pieRecibo());
        centrarEn(lineas, ACLARACION_LEGAL);
        lineas.add(DOBLE);

        return lineas;
    }

    /**
     * Nombre, NIT, dirección y teléfono, centrados.
     *
     * <p><strong>Cada dato que falte se omite y los demás se imprimen.</strong> Sin
     * nombre de tienda el recibo sale con el encabezado incompleto, y eso es correcto:
     * la clienta ya pagó y se va con el producto, así que un comprobante a medias vale
     * más que ninguno. La pantalla de configuración es la que avisa de lo que falta.
     */
    private static void encabezado(List<String> lineas, DatosTiendaDto tienda) {
        if (tienda == null) {
            return;
        }
        centrarEn(lineas, tienda.nombre());
        centrarEn(lineas, prefijo("NIT ", tienda.nit()));
        centrarEn(lineas, tienda.direccion());
        centrarEn(lineas, prefijo("Tel ", tienda.telefono()));
    }

    /**
     * Cómo se pagó.
     *
     * <p>Efectivo y cambio solo con EFECTIVO: un pago con datáfono no tiene vueltas que
     * dar, y una línea de "Cambio 0" en un pago por Nequi es ruido que hace dudar. Para
     * los demás métodos, una línea con el nombre — el importe ya está en TOTAL, justo
     * encima.
     */
    private static List<String> pago(Venta venta) {
        if (venta.getMetodoPago() != MetodoPago.EFECTIVO) {
            return List.of(nombreDe(venta.getMetodoPago()));
        }
        return List.of(
                alinear("Efectivo", pesos(venta.getEfectivoRecibido() == null
                        ? venta.getTotal() : venta.getEfectivoRecibido())),
                alinear("Cambio", pesos(venta.getCambio() == null ? 0 : venta.getCambio())));
    }

    /** Los métodos, dichos como se dicen en el mostrador. */
    private static String nombreDe(MetodoPago metodo) {
        return switch (metodo) {
            case EFECTIVO -> "Efectivo";
            case TARJETA -> "Tarjeta";
            case NEQUI -> "Nequi";
            case DAVIPLATA -> "Daviplata";
            case TRANSFERENCIA -> "Transferencia";
        };
    }

    private static String prefijo(String prefijo, String valor) {
        return valor == null || valor.isBlank() ? null : prefijo + valor.strip();
    }

    // ------------------------------------------------------- formato de líneas

    /**
     * Pesos colombianos con punto de miles y sin decimales. Manual y no
     * {@code NumberFormat} con locale: el formato del recibo no puede depender de la
     * configuración regional del PC donde corra la aplicación.
     */
    static String pesos(long monto) {
        return String.format(Locale.US, "%,d", monto).replace(',', '.');
    }

    /**
     * Etiqueta a la izquierda, importe a la derecha, relleno de espacios en medio.
     *
     * <p>Si no caben los dos, <strong>se recorta la etiqueta y nunca el importe</strong>:
     * un nombre a medias se entiende, un número a medias es una cifra equivocada.
     */
    static String alinear(String izquierda, String derecha) {
        String izq = limpiar(izquierda);
        String der = limpiar(derecha);

        int disponible = ANCHO - der.length() - 1;
        if (disponible < 0) {
            return der;
        }
        if (izq.length() > disponible) {
            izq = izq.substring(0, disponible);
        }
        return izq + " ".repeat(ANCHO - izq.length() - der.length()) + der;
    }

    /** Centra, y si el texto no cabe lo envuelve y centra cada línea. */
    static List<String> centrar(String texto) {
        List<String> centradas = new ArrayList<>();
        for (String linea : envolver(texto)) {
            int sobra = ANCHO - linea.length();
            centradas.add(" ".repeat(sobra / 2) + linea);
        }
        return centradas;
    }

    private static void centrarEn(List<String> lineas, String texto) {
        if (texto != null && !texto.isBlank()) {
            lineas.addAll(centrar(texto));
        }
    }

    /**
     * Envuelve a 42 columnas cortando por palabras.
     *
     * <p>Tres reglas, y las tres se ven en el papel:
     *
     * <ul>
     *   <li>Se corta entre palabras. Partir "Superstay" como "Supers/tay" hace ilegible
     *       lo único que la clienta va a leer para reconocer lo que compró.
     *   <li>Una palabra que sola pasa de 42 <strong>sí</strong> se parte, porque si no
     *       no cabría nunca. Pasa con los códigos de tono largos.
     *   <li>La última línea no se queda con una sola palabra corta. "5 ml" solo en la
     *       última línea se lee como si fuera otro producto.
     * </ul>
     */
    static List<String> envolver(String texto) {
        String limpio = limpiar(texto).strip();
        if (limpio.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();

        for (String palabra : limpio.split(" +")) {
            while (palabra.length() > ANCHO) {
                cerrar(lineas, actual);
                lineas.add(palabra.substring(0, ANCHO));
                palabra = palabra.substring(ANCHO);
            }
            if (palabra.isEmpty()) {
                continue;
            }
            if (actual.length() == 0) {
                actual.append(palabra);
            } else if (actual.length() + 1 + palabra.length() <= ANCHO) {
                actual.append(' ').append(palabra);
            } else {
                lineas.add(actual.toString());
                actual.setLength(0);
                actual.append(palabra);
            }
        }
        cerrar(lineas, actual);

        evitarHuerfana(lineas);
        return lineas;
    }

    private static void cerrar(List<String> lineas, StringBuilder actual) {
        if (actual.length() > 0) {
            lineas.add(actual.toString());
            actual.setLength(0);
        }
    }

    /**
     * Baja una palabra de la penúltima línea cuando la última quedó con una sola corta.
     * Si al bajarla no cabría, o la penúltima se quedaría vacía, se deja como estaba:
     * el remedio no puede desarmar el bloque entero.
     */
    private static void evitarHuerfana(List<String> lineas) {
        if (lineas.size() < 2) {
            return;
        }
        String ultima = lineas.get(lineas.size() - 1);
        if (ultima.contains(" ") || ultima.length() > HUERFANA_MAXIMA) {
            return;
        }

        String penultima = lineas.get(lineas.size() - 2);
        int corte = penultima.lastIndexOf(' ');
        if (corte < 0) {
            return;
        }
        String palabra = penultima.substring(corte + 1);
        if (palabra.length() + 1 + ultima.length() > ANCHO) {
            return;
        }

        lineas.set(lineas.size() - 2, penultima.substring(0, corte));
        lineas.set(lineas.size() - 1, palabra + " " + ultima);
    }

    /**
     * Deja el texto en lo que Courier con WinAnsiEncoding sabe pintar.
     *
     * <p>Las tildes y la eñe están cubiertas; lo que no lo está —una comilla tipográfica
     * pegada desde Word, un guion largo, un emoji en el nombre de un tono— haría que
     * PDFBox lance al escribir. Y como el fallo de generación se traga para no tumbar un
     * cobro, el resultado sería una venta sin recibo y sin explicación visible. Los
     * caracteres que tienen equivalente ASCII se convierten; el resto pasa a '?', que se
     * ve y se puede corregir en el catálogo.
     *
     * <p><strong>No recorta los espacios de los extremos.</strong> La sangría de dos
     * espacios de la línea de cantidad es la que la subordina visualmente a la
     * descripción de arriba, y recortarla aquí la borraba. Quien necesita el texto sin
     * espacios sobrantes —la envoltura— lo recorta por su cuenta.
     */
    static String limpiar(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder limpio = new StringBuilder(texto.length());
        for (char caracter : texto.toCharArray()) {
            switch (caracter) {
                case '\n', '\r', '\t' -> limpio.append(' ');
                case '—', '–' -> limpio.append('-');
                case '…' -> limpio.append("...");
                case '‘', '’' -> limpio.append('\'');
                case '“', '”' -> limpio.append('"');
                default -> limpio.append(imprimible(caracter) ? caracter : '?');
            }
        }
        return limpio.toString();
    }

    private static boolean imprimible(char caracter) {
        return caracter >= ' ' && caracter <= 'ÿ';
    }
}
