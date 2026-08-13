package com.alejandriamakeup.pos.catalogo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza un nombre para comparar duplicados: sin tildes ni diéresis, todo en
 * mayúsculas, sin espacios sobrantes.
 *
 * <p>Es el gemelo en Java de la expresión de los índices únicos de V4. La
 * integridad la impone el índice — funciona aunque alguien inserte por otra vía —
 * y esto existe para poder <strong>comparar antes</strong> y devolver un 409 que
 * nombre el registro con el que se choca. Un usuario que escribe "Loréal" y recibe
 * "ya existe LOREAL" entiende qué pasó; el mismo usuario recibiendo una violación
 * de índice, no.
 *
 * <p>Aquí se usa {@link Normalizer} con NFD, que descompone cada letra acentuada en
 * letra base más marca diacrítica, y se quitan las marcas. Eso cubre
 * <em>cualquier</em> diacrítico Unicode, no una lista.
 *
 * <p>De ahí una asimetría deliberada con el índice: el SQL solo dobla Latin-1, así
 * que este normalizador es <strong>más estricto</strong>. El conjunto que dobla el
 * SQL es un subconjunto del que dobla Java, y esa dirección es la segura: el índice
 * nunca puede rechazar un nombre que este servicio ya aprobó, así que nunca hay un
 * 500 sorpresa después de un 200. Lo único que se pierde es fuerza del respaldo para
 * nombres con diacríticos fuera de Latin-1.
 */
public final class NombreNormalizado {

    private NombreNormalizado() {
    }

    /**
     * @return el nombre comparable, o cadena vacía si el nombre es nulo
     */
    public static String de(String nombre) {
        if (nombre == null) {
            return "";
        }
        return Normalizer.normalize(nombre.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
    }

    /** Si dos nombres son el mismo a ojos de los índices únicos del catálogo. */
    public static boolean sonElMismo(String uno, String otro) {
        return de(uno).equals(de(otro));
    }
}
