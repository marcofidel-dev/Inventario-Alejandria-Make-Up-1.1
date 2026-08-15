package com.alejandriamakeup.pos.ventas;

/**
 * Reparte el descuento de cabecera entre las líneas de una venta.
 *
 * <p>Función pura y aparte del servicio a propósito: es aritmética entera con un
 * residuo, y probar cientos de combinaciones por HTTP costaría cientos de arranques
 * de contexto para verificar una suma. Aquí se barre en milisegundos.
 *
 * <p><strong>El residuo no se puede tirar.</strong> Truncando cada línea por su
 * cuenta se pierden pesos: cinco líneas con 25.000 de descuento pierden 3, y esos 3
 * dejan {@code SUM(descuento_prorrateado) != venta.descuento} para siempre, porque
 * el valor queda congelado en {@code venta_item}. La última línea absorbe la
 * diferencia, así que las dos igualdades se cumplen exactamente:
 *
 * <pre>
 *   SUM(descuentoProrrateado)            == descuento
 *   SUM(subtotal - descuentoProrrateado) == subtotal - descuento == total
 * </pre>
 */
public final class Prorrateo {

    private Prorrateo() {
    }

    /**
     * @param subtotales el subtotal de cada línea, en el orden en que se van a guardar
     * @param descuento  el descuento de cabecera, nunca negativo y nunca mayor que la
     *                   suma de {@code subtotales}
     * @return cuánto descuento le toca a cada línea, en el mismo orden
     */
    public static long[] repartir(long[] subtotales, long descuento) {
        long[] reparto = new long[subtotales.length];

        // Sin descuento no hay nada que repartir, y este corte además es la única
        // defensa que necesita la división de abajo: el subtotal solo puede ser 0 si
        // todas las líneas valen 0, y entonces el descuento no puede ser otra cosa
        // que 0 sin violar descuento <= subtotal. Vender a precio 0 es alcanzable
        // (una variante desactivada conserva su precio, y el esquema admite 0), así
        // que el caso no es teórico.
        if (descuento == 0) {
            return reparto;
        }

        long subtotal = 0;
        for (long linea : subtotales) {
            subtotal += linea;
        }
        if (subtotal <= 0) {
            throw new IllegalArgumentException(
                    "No se puede repartir un descuento de " + descuento + " sobre un subtotal de "
                            + subtotal + ". El servicio tenía que haberlo rechazado antes.");
        }

        long asignado = 0;
        for (int i = 0; i < subtotales.length - 1; i++) {
            reparto[i] = descuento * subtotales[i] / subtotal;
            asignado += reparto[i];
        }
        reparto[subtotales.length - 1] = descuento - asignado;
        return reparto;
    }
}
