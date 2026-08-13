package com.alejandriamakeup.pos.config;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persiste {@link LocalDate} como TEXT en {@code yyyy-MM-dd}, para las columnas
 * que son una fecha sin hora — hoy solo
 * {@code variante.fecha_vencimiento}.
 *
 * <p>Mismas dos razones que {@link LocalDateTimeIsoConverter}: sin el converter
 * el mapeo no pasa {@code ddl-auto: validate} contra una columna TEXT, y el
 * relleno con ceros hace que ordenar el texto sea ordenar cronológicamente.
 *
 * <p><strong>Al comparar en SQL, va contra {@code date('now')}, nunca contra
 * {@code datetime('now')}.</strong> Esta columna guarda {@code '2027-03-01'}, de
 * diez caracteres, mientras que {@code datetime('now')} produce
 * {@code '2027-03-01 00:00:00'}, de diecinueve. Comparar las dos como texto sale
 * mal siempre y en la dirección peligrosa: {@code '2027-03-01'} es menor que
 * {@code '2027-03-01 00:00:00'} porque la cadena corta termina primero, así que
 * un producto que vence hoy aparecería como ya vencido. Las columnas de
 * {@link LocalDateTimeIsoConverter} son el caso opuesto y sí van contra
 * {@code datetime('now')}.
 */
@Converter(autoApply = true)
public class LocalDateIsoConverter implements AttributeConverter<LocalDate, String> {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String convertToDatabaseColumn(LocalDate fecha) {
        return fecha == null ? null : fecha.format(FORMATO);
    }

    @Override
    public LocalDate convertToEntityAttribute(String texto) {
        return texto == null ? null : LocalDate.parse(texto, FORMATO);
    }
}
