package com.alejandriamakeup.pos.inventario;

/** Proyección de {@link MovimientoInventarioRepository#stockDeTodas()}. */
public interface StockPorVariante {

    Long getVarianteId();

    long getStock();
}
