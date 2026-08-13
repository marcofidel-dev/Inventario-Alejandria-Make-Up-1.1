package com.alejandriamakeup.pos.compras;

/**
 * Debe calzar con el CHECK de {@code compra.estado}.
 *
 * <p>Dos estados vigentes y dos terminales. La diferencia entre los terminales no
 * es de matiz: <strong>descartar no revierte nada porque nunca hubo mercancía;
 * anular sí devuelve el stock</strong>. Por eso son actos distintos, con botones
 * distintos y permisos distintos, y no una sola "cancelación".
 */
public enum EstadoCompra {

    /** Registrada pero sin recibir. No ha tocado el inventario. */
    BORRADOR,

    /** Recibida: generó sus movimientos de entrada y ya no se puede editar. */
    RECIBIDA,

    /**
     * Un BORRADOR que no llegó a recibirse. No existe ningún movimiento de
     * inventario con su id, porque nunca entró mercancía.
     */
    DESCARTADA,

    /**
     * Una RECIBIDA que se deshizo. Existen sus movimientos {@code COMPRA} y los
     * {@code ANULACION} que los compensan.
     *
     * <p>Para el cálculo del costo promedio, una compra anulada
     * <strong>nunca ocurrió</strong>: el replay del ledger salta sus dos
     * movimientos. Como netean a cero, el stock no cambia por saltarlos — lo que
     * cambia es el promedio ponderado, que es justo el punto.
     */
    ANULADA;

    /** Si desde este estado todavía se puede modificar la compra. */
    public boolean esVigente() {
        return this == BORRADOR || this == RECIBIDA;
    }

    /** Si la compra terminó sin quedar vigente, y por lo tanto exige motivo de baja. */
    public boolean esBaja() {
        return this == DESCARTADA || this == ANULADA;
    }
}
