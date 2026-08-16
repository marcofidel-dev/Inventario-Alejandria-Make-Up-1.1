package com.alejandriamakeup.pos.ventas.recibo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.configuracion.dto.DatosTiendaDto;
import com.alejandriamakeup.pos.ventas.EstadoVenta;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaItem;
import com.alejandriamakeup.pos.usuarios.Usuario;

/**
 * El formato del recibo, sin PDF de por medio.
 *
 * <p>Son tests de cadenas porque el recibo <strong>es</strong> una cadena: el PDF solo
 * la pinta con una fuente monoespaciada. Lo que se rompe aquí no se nota mirando la
 * pantalla — se nota con el papel en la mano y la clienta esperando.
 */
class ReciboTextoTest {

    private static final DatosTiendaDto TIENDA = new DatosTiendaDto(
            "Alejandria Make Up", "1.234.567.890-1", "Cra 10 # 15-30, Puerto Gaitán",
            "300 123 4567", "Gracias por su compra");

    // ------------------------------------------------------------------ ancho

    /**
     * <strong>La regla de la que dependen todas las demás.</strong> Una sola línea de
     * 43 se sale del área imprimible del papel de 80mm, y en pantalla el PDF se ve
     * perfecto: el corte solo aparece en la impresión.
     */
    @Test
    void ningunaLineaPasaDeCuarentaYDosColumnas() {
        List<String> lineas = ReciboTexto.de(
                venta(102_300, 0, MetodoPago.EFECTIVO, 110_000L, 7_700L),
                List.of(
                        item("Maybelline|Labial mate Superstay larga duración|Rojo carmín|5 ml",
                                2, 38_900),
                        item("Essence|Base líquida|Tono 3|30 ml", 1, 24_500)),
                TIENDA);

        lineas.forEach(linea -> System.out.println("    |" + linea + "|"));
        System.out.println("VERIFICACION " + lineas.size() + " líneas, la más larga de "
                + lineas.stream().mapToInt(String::length).max().orElse(0));

        assertThat(lineas).allSatisfy(linea ->
                assertThat(linea.length())
                        .withFailMessage("«%s» mide %d y el papel tiene %d columnas",
                                linea, linea.length(), ReciboTexto.ANCHO)
                        .isLessThanOrEqualTo(ReciboTexto.ANCHO));
    }

    // -------------------------------------------------------------- envoltura

    /**
     * Se corta entre palabras. Partir el nombre del producto haría ilegible lo único
     * que la clienta va a leer para reconocer lo que compró.
     */
    @Test
    void laEnvolturaCortaPorPalabrasYNoAMitadDePalabra() {
        List<String> lineas = ReciboTexto.envolver(
                "Maybelline|Máscara de pestañas Lash Sensational Sky High|Negro intenso|9 ml");

        lineas.forEach(linea -> System.out.println("    |" + linea + "|"));

        assertThat(lineas).hasSizeGreaterThan(1);
        // Ninguna palabra del original quedó partida: al volver a unir con espacios sale
        // exactamente el texto de partida.
        assertThat(String.join(" ", lineas))
                .isEqualTo("Maybelline|Máscara de pestañas Lash Sensational Sky High|Negro "
                        + "intenso|9 ml");
    }

    /** Una palabra que sola pasa de 42 sí se parte: si no, no cabría nunca. */
    @Test
    void unaPalabraMasLargaQueLaLineaSiSePparte() {
        String larguisima = "X".repeat(50);
        List<String> lineas = ReciboTexto.envolver("Tono " + larguisima);

        lineas.forEach(linea -> System.out.println("    |" + linea + "|"));

        assertThat(lineas).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(42));
        assertThat(String.join("", lineas)).contains(larguisima.substring(0, 42));
    }

    /**
     * La última línea no se queda con una sola palabra corta.
     *
     * <p>"5 ml" solo en la última línea se lee como si fuera otro producto de la lista.
     * El arreglo es bajar una palabra de la línea anterior, no ensanchar nada.
     */
    @Test
    void laUltimaLineaNoQuedaConUnaSolaPalabraCorta() {
        // Medido a propósito para que la envoltura ingenua deje "9ml" solo abajo.
        List<String> lineas = ReciboTexto.envolver(
                "Maybelline|Mascara de pestanas Lash Sensational|Negro 9ml");

        lineas.forEach(linea -> System.out.println("    |" + linea + "|"));
        String ultima = lineas.get(lineas.size() - 1);
        System.out.println("VERIFICACION última línea => «" + ultima + "»");

        assertThat(lineas).hasSize(2);
        assertThat(ultima)
                .withFailMessage("La última línea quedó con una sola palabra corta: «%s»", ultima)
                .contains(" ");
    }

    // ---------------------------------------------------- línea de cantidades

    /**
     * La línea de cantidad va aparte, alineada a la derecha y <strong>nunca
     * envuelta</strong>: es la que se lee en diagonal para comprobar una cuenta.
     */
    @Test
    void laLineaDeCantidadNoSeEnvuelveYAlineaElImporteALaDerecha() {
        List<String> lineas = ReciboTexto.de(
                venta(77_800, 0, MetodoPago.EFECTIVO, 80_000L, 2_200L),
                List.of(item("Maybelline|Labial mate Superstay que no cabe de ningún modo en una "
                        + "sola línea|Rojo carmín|5 ml", 2, 38_900)),
                TIENDA);

        String cantidad = lineas.stream().filter(l -> l.contains(" x ")).findFirst().orElseThrow();
        System.out.println("VERIFICACION línea de cantidad => |" + cantidad + "|");

        assertThat(lineas).filteredOn(l -> l.contains(" x ")).hasSize(1);
        assertThat(cantidad).startsWith("  2 x 38.900").endsWith("77.800");
        assertThat(cantidad).hasSize(ReciboTexto.ANCHO);
    }

    /** Miles con punto y sin decimales, y sin depender del locale del PC. */
    @Test
    void losMontosVanConPuntoDeMilesYSinDecimales() {
        System.out.println("VERIFICACION montos => " + ReciboTexto.pesos(1_234_567) + " / "
                + ReciboTexto.pesos(5_000) + " / " + ReciboTexto.pesos(0));

        assertThat(ReciboTexto.pesos(1_234_567)).isEqualTo("1.234.567");
        assertThat(ReciboTexto.pesos(5_000)).isEqualTo("5.000");
        assertThat(ReciboTexto.pesos(0)).isEqualTo("0");
    }

    // ------------------------------------------------------------- descuentos

    /** Un "DESCUENTO 0" invita a preguntar por un descuento que nadie aplicó. */
    @Test
    void laLineaDeDescuentoDesapareceCuandoEsCero() {
        List<String> conDescuento = ReciboTexto.de(
                venta(97_300, 5_000, MetodoPago.EFECTIVO, 100_000L, 2_700L),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 2, 38_900)), TIENDA);
        List<String> sinDescuento = ReciboTexto.de(
                venta(102_300, 0, MetodoPago.EFECTIVO, 110_000L, 7_700L),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 2, 38_900)), TIENDA);

        System.out.println("VERIFICACION con descuento => "
                + conDescuento.stream().filter(l -> l.startsWith("DESCUENTO")).toList());
        System.out.println("VERIFICACION sin descuento => "
                + sinDescuento.stream().filter(l -> l.startsWith("DESCUENTO")).toList());

        assertThat(conDescuento).anySatisfy(l -> assertThat(l).startsWith("DESCUENTO")
                .endsWith("-5.000"));
        assertThat(sinDescuento).noneSatisfy(l -> assertThat(l).startsWith("DESCUENTO"));
    }

    // ------------------------------------------------------------------- pago

    /** Efectivo y cambio solo con EFECTIVO: un datáfono no da vueltas. */
    @Test
    void efectivoYCambioSoloAparecenConPagoEnEfectivo() {
        List<String> enEfectivo = ReciboTexto.de(
                venta(97_300, 0, MetodoPago.EFECTIVO, 100_000L, 2_700L),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 1, 97_300)), TIENDA);
        List<String> conTarjeta = ReciboTexto.de(
                venta(97_300, 0, MetodoPago.TARJETA, null, null),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 1, 97_300)), TIENDA);

        System.out.println("VERIFICACION efectivo => "
                + enEfectivo.stream().filter(l -> l.startsWith("Efectivo")
                        || l.startsWith("Cambio")).toList());
        System.out.println("VERIFICACION tarjeta => "
                + conTarjeta.stream().filter(l -> l.startsWith("Tarjeta")).toList());

        assertThat(enEfectivo).anySatisfy(l -> assertThat(l).startsWith("Efectivo")
                .endsWith("100.000"));
        assertThat(enEfectivo).anySatisfy(l -> assertThat(l).startsWith("Cambio")
                .endsWith("2.700"));

        assertThat(conTarjeta).contains("Tarjeta");
        assertThat(conTarjeta).noneSatisfy(l -> assertThat(l).startsWith("Cambio"));
        assertThat(conTarjeta).noneSatisfy(l -> assertThat(l).startsWith("Efectivo"));
    }

    // --------------------------------------------------------- lo que no dice

    /**
     * <strong>Nunca la palabra FACTURA</strong>, salvo en el pie que aclara que este
     * documento no lo es. No está validado por la DIAN, y un papel que se llame factura
     * es un problema legal, no un detalle de redacción.
     */
    @Test
    void diceReciboYNuncaFacturaSalvoParaNegarlo() {
        List<String> lineas = ReciboTexto.de(
                venta(38_900, 0, MetodoPago.NEQUI, null, null),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 1, 38_900)), TIENDA);

        List<String> conFactura = lineas.stream()
                .filter(l -> l.toLowerCase().contains("factura")).toList();
        System.out.println("VERIFICACION menciones de 'factura' => " + conFactura);

        assertThat(lineas).anySatisfy(l -> assertThat(l).contains("RECIBO DE VENTA"));
        assertThat(conFactura).containsExactly("     Este documento no es una factura");
    }

    /**
     * <strong>Ni costos ni márgenes.</strong> El recibo se le entrega a la clienta, y
     * el costo congelado vive en la misma fila de la que sale todo lo que se imprime:
     * basta con un campo de más en el mapeo para que salga en el papel.
     */
    @Test
    void noImprimeNiCostosNiMargenes() {
        VentaItem item = item("Maybelline|Labial|Rojo|5 ml", 2, 38_900);

        List<String> lineas = ReciboTexto.de(
                venta(77_800, 0, MetodoPago.EFECTIVO, 80_000L, 2_200L), List.of(item), TIENDA);
        String todo = String.join("\n", lineas);

        System.out.println("VERIFICACION costo congelado de la línea => "
                + item.getCostoUnitarioCongelado() + ", ¿aparece impreso? "
                + todo.contains(String.valueOf(item.getCostoUnitarioCongelado())));

        assertThat(todo.toLowerCase()).doesNotContain("costo").doesNotContain("margen");
        assertThat(todo).doesNotContain(String.valueOf(item.getCostoUnitarioCongelado()));
    }

    // -------------------------------------------------------------- encabezado

    /**
     * <strong>Sin nombre de tienda el recibo se emite igual.</strong> La clienta ya
     * pagó y se va con el producto: un encabezado incompleto vale más que ningún
     * comprobante. Lo que falta lo advierte la pantalla de configuración, no el papel.
     */
    @Test
    void sinDatosDeTiendaElReciboSeGeneraIgualConElEncabezadoIncompleto() {
        DatosTiendaDto enBlanco = new DatosTiendaDto("", "", "", "", "");

        List<String> lineas = ReciboTexto.de(
                venta(38_900, 0, MetodoPago.EFECTIVO, 40_000L, 1_100L),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 1, 38_900)), enBlanco);

        lineas.forEach(linea -> System.out.println("    |" + linea + "|"));

        // El cuerpo del recibo está entero: consecutivo, líneas, total y la aclaración.
        assertThat(lineas).anySatisfy(l -> assertThat(l).contains("RECIBO DE VENTA"));
        assertThat(lineas).anySatisfy(l -> assertThat(l).startsWith("TOTAL"));
        assertThat(lineas).anySatisfy(l -> assertThat(l).contains("no es una factura"));
        // Y no quedan líneas huecas de encabezado: "NIT" y "Tel" sin valor no se imprimen.
        assertThat(lineas).noneSatisfy(l -> assertThat(l.strip()).isEqualTo("NIT"));
        assertThat(lineas).noneSatisfy(l -> assertThat(l.strip()).isEqualTo("Tel"));
    }

    /** Un dato presente y otro ausente: se imprime el que hay y se calla el que no. */
    @Test
    void elEncabezadoOmiteSoloLoQueFalta() {
        List<String> lineas = ReciboTexto.de(
                venta(38_900, 0, MetodoPago.EFECTIVO, 40_000L, 1_100L),
                List.of(item("Maybelline|Labial|Rojo|5 ml", 1, 38_900)),
                new DatosTiendaDto("Alejandria Make Up", "", "", "300 123 4567", ""));

        String encabezado = String.join("\n", lineas.subList(0, 5));
        System.out.println("VERIFICACION encabezado parcial =>\n" + encabezado);

        assertThat(encabezado).contains("Alejandria Make Up").contains("Tel 300 123 4567");
        assertThat(encabezado).doesNotContain("NIT");
    }

    // ------------------------------------------------- separador y saneamiento

    /**
     * El separador que se imprime es el mismo que se congeló. Sin espacios alrededor:
     * con ellos, " | " cuenta como palabra en la envoltura y puede quedar solo al
     * principio de una línea.
     */
    @Test
    void elSeparadorDeLaDescripcionSeImprimeTalCualSeCongelo() {
        List<String> lineas = ReciboTexto.de(
                venta(38_900, 0, MetodoPago.EFECTIVO, 40_000L, 1_100L),
                List.of(item("Maybelline|Labial mate|Rojo|5 ml", 1, 38_900)), TIENDA);

        System.out.println("VERIFICACION descripción impresa => "
                + lineas.stream().filter(l -> l.contains("Maybelline")).toList());

        assertThat(lineas).contains("Maybelline|Labial mate|Rojo|5 ml");
        assertThat(lineas).noneSatisfy(l -> assertThat(l).contains(" | "));
    }

    /**
     * Tildes y eñe se imprimen tal cual —WinAnsiEncoding las cubre—, y lo que la
     * codificación no cubre no puede hacer estallar la generación: pasa a '?' y el
     * recibo sale.
     */
    @Test
    void lasTildesSobrevivenYLoQueCourierNoSabePintarNoRompeElRecibo() {
        String conEmoji = "Máscara de pestañas 💄 edición limitada";
        String limpio = ReciboTexto.limpiar(conEmoji);

        System.out.println("VERIFICACION saneado => «" + limpio + "»");

        assertThat(limpio).contains("Máscara de pestañas").contains("edición limitada");
        assertThat(limpio).doesNotContain("💄");
        // El guion largo y los puntos suspensivos pegados desde Word tienen equivalente.
        assertThat(ReciboTexto.limpiar("Tono — nuevo…")).isEqualTo("Tono - nuevo...");
    }

    // ------------------------------------------------------------------ apoyo

    private static Venta venta(long total, long descuento, MetodoPago metodo,
                               Long recibido, Long cambio) {
        Usuario camila = new Usuario();
        camila.setNombre("Camila");

        Venta venta = new Venta();
        venta.setConsecutivo("V-000123");
        venta.setFecha(LocalDateTime.of(2026, 8, 15, 14, 32));
        venta.setUsuario(camila);
        venta.setSubtotal(total + descuento);
        venta.setDescuento(descuento);
        venta.setTotal(total);
        venta.setMetodoPago(metodo);
        venta.setEfectivoRecibido(recibido);
        venta.setCambio(cambio);
        venta.setEstado(EstadoVenta.COMPLETADA);
        return venta;
    }

    private static VentaItem item(String descripcion, int cantidad, long precio) {
        return VentaItem.builder()
                .variante(new Variante())
                .cantidad(cantidad)
                .precioUnitarioCongelado(precio)
                // Un costo irrepetible: si aparece en el papel, se sabe de dónde salió.
                .costoUnitarioCongelado(919_193)
                .descripcionCongelada(descripcion)
                .descuentoProrrateado(0)
                .subtotal(cantidad * precio)
                .build();
    }
}
