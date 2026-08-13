package com.alejandriamakeup.pos.inventario;

import com.alejandriamakeup.pos.compras.EstadoCompra;

/**
 * Proyección de {@link MovimientoInventarioRepository#historialParaCosteo(Long)}.
 *
 * <p>Solo lo que el recálculo del costo promedio necesita leer de cada movimiento:
 * cuánto entró o salió, a qué costo, y si el movimiento pertenece a una compra que
 * después se anuló.
 */
public interface MovimientoParaCosteo {

    int getCantidad();

    long getCostoUnitario();

    /**
     * El estado de la compra de la que viene este movimiento, o {@code null} si no
     * viene de ninguna — una carga inicial, un ajuste, una venta.
     */
    EstadoCompra getEstadoCompra();
}
