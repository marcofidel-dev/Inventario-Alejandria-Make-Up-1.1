package com.alejandriamakeup.pos.dinero;

/**
 * El margen sobre precio de venta, en un solo sitio.
 *
 * <p><strong>Sobre el precio, no sobre el costo.</strong> Comprar a 6.000 y vender a
 * 10.000 es 40% de margen y 66% de sobreprecio. Los dos números son ciertos y
 * describen la misma operación, así que ninguna fórmula puede "detectar" que se está
 * usando el otro: el error sale a la luz meses después, comparando el informe con la
 * realidad del banco. Por eso la fórmula vive aquí y no se reescribe en cada módulo.
 *
 * <p>Lo escribieron tres sitios antes de esta clase —la previa de recepción, el
 * listado de costos del catálogo y, al llegar, las métricas— y ya diferían en el
 * borde: uno devolvía {@code 0} con precio cero y otro {@code null}. Esa clase de
 * divergencia es la que hace que dos pantallas del mismo sistema muestren números
 * distintos para el mismo producto.
 */
public final class Margen {

    /**
     * Por debajo de esto, la recepción avisa.
     *
     * <p>Vive en el backend y no en el front a propósito: la pantalla muestra la
     * advertencia que le manda el servidor, no una que calcule por su cuenta. Es el
     * mismo criterio que "stock bajo" — dos implementaciones del mismo umbral acaban
     * discrepando, y entonces nadie sabe cuál creer.
     */
    public static final int MINIMO_PORCENTAJE = 20;

    private Margen() {
    }

    /** Lo que queda: {@code ingreso - costo}. Puede ser negativo, y eso es un dato. */
    public static long de(long ingreso, long costo) {
        return ingreso - costo;
    }

    /**
     * El porcentaje redondeado, <strong>solo para mostrar</strong>. Ninguna decisión
     * del sistema se toma sobre este número: ver {@link #porDebajoDelMinimo}.
     *
     * @return {@code null} si el ingreso es cero o negativo — no existe el porcentaje
     *         de nada, y un {@code 0} ahí se leería como "margen nulo", que es otra
     *         cosa
     */
    public static Integer porcentaje(long ingreso, long costo) {
        if (ingreso <= 0) {
            return null;
        }
        return (int) Math.round(de(ingreso, costo) * 100.0 / ingreso);
    }

    /**
     * Si el margen queda por debajo del mínimo, con aritmética entera exacta.
     *
     * <p>No se compara el porcentaje redondeado: un margen de 19,6% redondeado a 20
     * dejaría de encender el aviso justo en el caso que el aviso existe para atrapar.
     *
     * <p>Un precio de cero o negativo cuenta siempre como margen bajo — no hay forma
     * de ganar el 20% de nada.
     */
    public static boolean porDebajoDelMinimo(long precio, long costo) {
        if (precio <= 0) {
            return true;
        }
        return de(precio, costo) * 100 < (long) MINIMO_PORCENTAJE * precio;
    }
}
