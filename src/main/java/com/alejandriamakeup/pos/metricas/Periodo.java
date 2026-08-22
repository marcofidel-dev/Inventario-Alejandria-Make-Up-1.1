package com.alejandriamakeup.pos.metricas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Día, semana o mes, y el periodo inmediatamente anterior con el que se compara.
 *
 * <p>El anterior es <strong>el mismo periodo corrido hacia atrás</strong>, no "los
 * últimos N días": el mes anterior a marzo es febrero completo, con sus 28 días, y
 * no los 31 días previos al 1 de marzo. Comparar marzo contra "31 días atrás"
 * mezclaría dos meses y daría una diferencia que no significa nada.
 *
 * <p>La semana empieza el <strong>lunes</strong>. Es la convención ISO y la que
 * usa el calendario colombiano; con semanas que empiecen el domingo, el sábado
 * —que en una tienda de barrio es el día fuerte— cae en una semana distinta según
 * quién haga la cuenta.
 *
 * <p>Los rangos son {@code [desde, hastaExclusivo)}, la misma forma que espera
 * {@code VentaItemRepository.lineasVendidas}.
 */
public enum Periodo {

    DIA,
    SEMANA,
    MES;

    /** Un rango de fechas, abierto por arriba. */
    public record Rango(LocalDate desde, LocalDate hastaExclusivo) {

        /** El último día que sí entra. Solo para mostrar: nadie lee "hasta el 1 de abril". */
        public LocalDate hastaInclusivo() {
            return hastaExclusivo.minusDays(1);
        }
    }

    /** El periodo que contiene esa fecha. */
    public Rango rangoDe(LocalDate fecha) {
        return switch (this) {
            case DIA -> new Rango(fecha, fecha.plusDays(1));
            case SEMANA -> {
                LocalDate lunes = fecha.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Rango(lunes, lunes.plusWeeks(1));
            }
            case MES -> {
                LocalDate primero = fecha.withDayOfMonth(1);
                yield new Rango(primero, primero.plusMonths(1));
            }
        };
    }

    /** El periodo inmediatamente anterior a ese, para el comparativo. */
    public Rango anteriorDe(LocalDate fecha) {
        return switch (this) {
            case DIA -> rangoDe(fecha.minusDays(1));
            case SEMANA -> rangoDe(fecha.minusWeeks(1));
            case MES -> rangoDe(fecha.withDayOfMonth(1).minusMonths(1));
        };
    }
}
