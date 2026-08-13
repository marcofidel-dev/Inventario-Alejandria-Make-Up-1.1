package com.alejandriamakeup.pos.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persiste {@link LocalDateTime} como TEXT en ISO-8601, que es lo que declara el
 * esquema.
 *
 * <p>Dos razones, y ninguna es cosmética.
 *
 * <p>La primera es que sin esto la app no arranca. Con {@code ddl-auto: validate},
 * Hibernate compara el tipo de cada columna y aborta si no calza. El dialecto de
 * SQLite emite {@code timestamp} (código JDBC 93) para un {@code LocalDateTime},
 * y las columnas de fecha del esquema son {@code TEXT} (código 12): no son tipos
 * equivalentes ni por código ni por nombre. Al convertir el atributo a
 * {@code String}, ante JDBC pasa a ser VARCHAR y calza con TEXT.
 *
 * <p>La segunda es que así el formato es nuestro y no del driver. El patrón tiene
 * ancho fijo y va con ceros a la izquierda, de modo que el orden lexicográfico
 * del texto <strong>es</strong> el orden cronológico: {@code ORDER BY fecha} en
 * SQL da el mismo resultado que ordenar los {@code LocalDateTime} en Java. Un
 * formato sin relleno (por ejemplo {@code 2026-8-1 9:05:00}) rompería eso en
 * silencio.
 */
@Converter(autoApply = true)
public class LocalDateTimeIsoConverter implements AttributeConverter<LocalDateTime, String> {

    /** {@code 'YYYY-MM-DD HH:MM:SS'}, el formato que promete el esquema. */
    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String convertToDatabaseColumn(LocalDateTime fecha) {
        return fecha == null ? null : fecha.format(FORMATO);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String texto) {
        return texto == null ? null : LocalDateTime.parse(texto, FORMATO);
    }
}
