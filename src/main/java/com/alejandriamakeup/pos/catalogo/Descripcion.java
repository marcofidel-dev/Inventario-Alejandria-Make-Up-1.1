package com.alejandriamakeup.pos.catalogo;

import java.util.stream.Stream;

/**
 * Cómo se nombra una variante, en un solo sitio.
 *
 * <p>La misma cadena aparece en tres lugares que tienen que decir lo mismo: el
 * buscador y el carrito del mostrador, el listado de ventas y el recibo impreso. La
 * arma <strong>el backend</strong> y el front la muestra tal cual — no la reconstruye.
 * Si cada lado la construyera por su cuenta, el día que una de las dos versiones
 * cambiara —un separador, un campo vacío tratado distinto— el carrito mostraría una
 * descripción y el recibo otra para la misma venta, y nadie lo notaría hasta tener el
 * papel en la mano.
 *
 * <p><strong>El separador es la barra vertical pegada, sin espacios.</strong> Con
 * espacios, {@code " | "} cuenta como palabra en la envoltura del recibo y puede
 * quedar sola al principio de la línea siguiente, que se ve peor que el problema que
 * resuelve. Sin espacios ahorra dos caracteres por separador, y con 42 de ancho eso
 * decide si una descripción cabe en una línea o en dos. Además es ASCII puro.
 *
 * <p><strong>Por eso hay que sanear.</strong> Una barra dentro de un nombre de
 * producto, tono o tamaño vuelve ambigua la lectura —no se sabe dónde termina un
 * campo— y como la descripción se congela en {@code venta_item}, ese error queda
 * para siempre. Se sustituye por {@code /}, que se lee; borrarla pegaría dos palabras
 * y mentiría.
 */
public final class Descripcion {

    /** Barra vertical pegada. Ver el porqué en la documentación de la clase. */
    public static final String SEPARADOR = "|";

    private Descripcion() {
    }

    /**
     * Marca, producto, tono y tamaño: lo que distingue una variante de otra. Los
     * campos vacíos no dejan separadores huérfanos.
     */
    public static String de(String marca, String producto, String tono, String tamano) {
        return Stream.of(marca, producto, tono, tamano)
                .filter(parte -> parte != null && !parte.isBlank())
                .map(Descripcion::sanear)
                .reduce((una, otra) -> una + SEPARADOR + otra)
                .orElse("");
    }

    private static String sanear(String parte) {
        return parte.strip().replace(SEPARADOR, "/");
    }
}
