package com.alejandriamakeup.pos.ventas;

/**
 * Debe calzar con el CHECK de {@code venta.metodo_pago}.
 *
 * <p>Solo {@link #EFECTIVO} toca el cajón: los demás medios no generan
 * movimiento de caja, se concilian aparte contra el extracto.
 */
public enum MetodoPago {
    EFECTIVO,
    TARJETA,
    NEQUI,
    DAVIPLATA,
    TRANSFERENCIA
}
