package com.alejandriamakeup.pos.config;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * El reloj del sistema, truncado a segundos.
 *
 * <p>Existe porque el esquema guarda las fechas como TEXT
 * {@code 'YYYY-MM-DD HH:MM:SS'} y ahí no caben los nanosegundos. Usar
 * {@code LocalDateTime.now()} directamente hace que una entidad recién creada
 * lleve en memoria una precisión que la base no tiene, y entonces la misma fecha
 * sale distinta según de dónde venga: {@code 14:16:38.415980900} en la respuesta
 * de un POST y {@code 14:16:38} al releerla después. Eso rompe comparaciones de
 * igualdad y desconcierta a cualquier cliente que parsee las dos.
 *
 * <p>Truncando al crear, lo que está en memoria es exactamente lo que quedó
 * guardado.
 */
public final class Fechas {

    private Fechas() {
    }

    /** Ahora, con la misma precisión que el esquema puede almacenar. */
    public static LocalDateTime ahora() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
