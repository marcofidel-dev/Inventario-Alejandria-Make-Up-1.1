package com.alejandriamakeup.pos.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Las dos igualdades del descuento prorrateado, barridas a mano alzada.
 *
 * <pre>
 *   SUM(descuento_prorrateado)            == venta.descuento
 *   SUM(subtotal - descuento_prorrateado) == venta.total
 * </pre>
 *
 * <p>Sin Spring a propósito. {@link Prorrateo} es una función pura, así que aquí
 * caben miles de combinaciones en milisegundos; por HTTP serían miles de peticiones
 * para verificar una suma de enteros. Que las igualdades se cumplan <em>de verdad</em>
 * contra la base de datos lo comprueba {@code VentaHttpTest}, que lee lo que quedó
 * guardado en {@code venta_item}.
 *
 * <p>La falla que esto atrapa no se ve en pantalla: el total cobrado es correcto —lo
 * calcula el servidor restando— y lo que queda mal es la suma de los descuentos
 * congelados en las líneas. Se descubre meses después, cuando el margen por producto
 * no cuadra con el margen de la venta y ya no hay forma de saber cuál de los dos
 * mentía.
 */
class DescuentoProrrateadoTest {

    /**
     * El caso real: cinco líneas y 25.000 de descuento.
     *
     * <p>Truncando cada línea por su cuenta se pierden exactamente 3 pesos. El test
     * calcula primero la versión ingenua y demuestra que pierde, y después exige que
     * {@link Prorrateo} no pierda: sin la primera mitad, esto sería una prueba que
     * pasa por casualidad sobre números que daban redondos.
     */
    @Test
    void laUltimaLineaAbsorbeElResiduoQueElTruncamientoPierde() {
        // Un carrito de mostrador: cinco líneas, una de ellas de tres unidades.
        long[] subtotales = { 45_900, 28_700, 73_500, 24_500, 21_400 };
        long subtotal = 194_000;
        long descuento = 25_000;

        long ingenuo = 0;
        for (long linea : subtotales) {
            ingenuo += descuento * linea / subtotal;
        }

        long[] reparto = Prorrateo.repartir(subtotales, descuento);
        long repartido = 0;
        for (long parte : reparto) {
            repartido += parte;
        }

        System.out.println("VERIFICACION 5 líneas, descuento 25.000:");
        System.out.println("    truncando cada línea  => " + ingenuo + " (faltan "
                + (descuento - ingenuo) + ")");
        System.out.println("    con residuo en la última => " + repartido
                + " " + java.util.Arrays.toString(reparto));

        assertThat(ingenuo).isEqualTo(24_997);
        assertThat(repartido).isEqualTo(descuento);
        // Solo la última cambia: las demás conservan su parte truncada.
        assertThat(reparto).containsExactly(5_914, 3_698, 9_471, 3_157, 2_760);
    }

    /**
     * El barrido. Semilla fija: si algún caso falla, falla siempre el mismo y se puede
     * reproducir.
     */
    @Test
    void lasDosIgualdadesSeCumplenExactamenteEnMilCombinaciones() {
        Random azar = new Random(20_260_814L);
        int comprobadas = 0;

        for (int caso = 0; caso < 1_000; caso++) {
            int lineas = 1 + azar.nextInt(12);
            long[] subtotales = new long[lineas];
            long subtotal = 0;

            for (int i = 0; i < lineas; i++) {
                // Precios y cantidades del mostrador: entre 100 y 300.000 pesos.
                subtotales[i] = (1 + azar.nextInt(3_000)) * 100L;
                subtotal += subtotales[i];
            }

            // Cualquier descuento válido, incluidos los dos extremos: 0 y todo.
            long descuento = switch (caso % 7) {
                case 0 -> 0;
                case 1 -> subtotal;
                default -> (long) (azar.nextDouble() * subtotal);
            };

            comprobar(subtotales, descuento, subtotal);
            comprobadas++;
        }

        System.out.println("VERIFICACION " + comprobadas
                + " combinaciones de líneas y descuentos: las dos igualdades exactas");
        assertThat(comprobadas).isEqualTo(1_000);
    }

    /**
     * Los bordes que el azar no garantiza tocar, incluida la división por cero.
     *
     * <p>Todas las líneas a precio 0 es alcanzable: una variante desactivada conserva
     * su precio y el esquema admite el 0. Sin el corte por {@code descuento == 0}, ese
     * carrito reventaría con {@code ArithmeticException} en el momento de cobrar.
     */
    @Test
    void losBordes() {
        System.out.println("VERIFICACION bordes:");

        // Una sola línea: se lleva todo el descuento.
        assertThat(Prorrateo.repartir(new long[] { 50_000 }, 7_000)).containsExactly(7_000);

        // Sin descuento: ceros, y ninguna división.
        assertThat(Prorrateo.repartir(new long[] { 50_000, 30_000 }, 0)).containsExactly(0, 0);

        // Subtotal 0 y descuento 0: el caso que dividiría por cero. Devuelve ceros.
        long[] gratis = Prorrateo.repartir(new long[] { 0, 0, 0 }, 0);
        System.out.println("    subtotal 0, descuento 0 => " + java.util.Arrays.toString(gratis));
        assertThat(gratis).containsExactly(0, 0, 0);

        // Descuento igual al subtotal: total 0, y nada negativo.
        comprobar(new long[] { 3_333, 6_667, 1 }, 10_001, 10_001);
        System.out.println("    descuento == subtotal => reparto completo sin negativos");
    }

    private void comprobar(long[] subtotales, long descuento, long subtotal) {
        long[] reparto = Prorrateo.repartir(subtotales, descuento);

        long sumaDescuentos = 0;
        long sumaNetas = 0;
        for (int i = 0; i < subtotales.length; i++) {
            assertThat(reparto[i])
                    .withFailMessage("La línea %d recibió un descuento negativo: %d — el esquema "
                            + "lo rechazaría con el CHECK descuento_prorrateado >= 0",
                            i, reparto[i])
                    .isNotNegative();
            sumaDescuentos += reparto[i];
            sumaNetas += subtotales[i] - reparto[i];
        }

        assertThat(sumaDescuentos)
                .withFailMessage("SUM(descuento_prorrateado)=%d != venta.descuento=%d "
                        + "sobre %s", sumaDescuentos, descuento,
                        java.util.Arrays.toString(subtotales))
                .isEqualTo(descuento);

        assertThat(sumaNetas)
                .withFailMessage("SUM(subtotal - prorrateado)=%d != venta.total=%d "
                        + "sobre %s", sumaNetas, subtotal - descuento,
                        java.util.Arrays.toString(subtotales))
                .isEqualTo(subtotal - descuento);
    }
}
