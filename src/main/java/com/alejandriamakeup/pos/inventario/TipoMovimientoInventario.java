package com.alejandriamakeup.pos.inventario;

/** Debe calzar con el CHECK de {@code movimiento_inventario.tipo}. */
public enum TipoMovimientoInventario {

    /**
     * El inventario que ya estaba en las vitrinas cuando el sistema empezó a
     * existir. Tiene su propio tipo y no se registra como {@link #AJUSTE} porque un
     * ajuste significa que alguien contó y no cuadró: meterlos en el mismo cajón
     * haría que el primer informe de descuadres mostrara todo el inventario de la
     * tienda como un error de conteo.
     *
     * <p>Una variante admite <strong>una sola</strong> carga inicial. Lo que venga
     * después es un ajuste.
     */
    CARGA_INICIAL,

    COMPRA,
    VENTA,
    AJUSTE,
    ANULACION,
    MERMA
}
