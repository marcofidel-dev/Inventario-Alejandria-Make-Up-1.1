package com.alejandriamakeup.pos.caja;

/**
 * Debe calzar con el CHECK de {@code movimiento_caja.tipo}.
 *
 * <p>Cada tipo sabe en qué dirección mueve el cajón, y el signo lo pone el
 * servidor. El cliente manda siempre un monto positivo: si mandara el signo, un
 * RETIRO con monto positivo inflaría el cajón y el arqueo cuadraría con plata que
 * no está.
 */
public enum TipoMovimientoCaja {

    /** Entra plata: cobro en efectivo. */
    VENTA_EFECTIVO(1),

    /** Sale plata: se saca del cajón. */
    RETIRO(-1),

    /** Entra plata que no es una venta. */
    INGRESO(1),

    /** Sale plata para pagar algo. */
    GASTO(-1),

    /** Sale plata: se devuelve un cobro. */
    ANULACION(-1);

    private final int signo;

    TipoMovimientoCaja(int signo) {
        this.signo = signo;
    }

    /** Aplica el signo del tipo a un monto positivo. */
    public long conSigno(long montoPositivo) {
        return signo * montoPositivo;
    }

    /** Los que se pueden registrar a mano; los demás nacen de una venta. */
    public boolean esManual() {
        return this == RETIRO || this == INGRESO || this == GASTO;
    }
}
