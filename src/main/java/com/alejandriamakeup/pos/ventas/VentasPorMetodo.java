package com.alejandriamakeup.pos.ventas;

/**
 * Cuánto se vendió por cada método de pago dentro de una sesión.
 *
 * <p>Solo el efectivo toca el cajón, así que el arqueo cuadra únicamente contra la
 * línea de {@link MetodoPago#EFECTIVO}. Las demás entran en la sesión pero se
 * concilian aparte contra el extracto, y por eso el cierre las muestra: sin ese
 * desglose, quien cierra no tiene forma de saber si el día fue flojo o si
 * simplemente se pagó todo con datáfono.
 */
public record VentasPorMetodo(MetodoPago metodo, long cantidad, long total) {
}
