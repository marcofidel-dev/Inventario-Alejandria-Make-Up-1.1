package com.alejandriamakeup.pos.inventario.dto;

import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;

/**
 * Un movimiento del ledger tal como sale de los endpoints de inventario.
 *
 * <p>Lleva {@code costoUnitario} porque los dos endpoints que devuelven esto — carga
 * inicial y ajustes — exigen permisos que solo tiene la DUENA. No debe aparecer en
 * ninguna respuesta accesible a la EMPLEADA, y el barrido de fuga de costos lo
 * vigila recorriendo todos los GET con su sesión.
 */
public record MovimientoInventarioDto(
        Long id,
        Long varianteId,
        TipoMovimientoInventario tipo,
        int cantidad,
        long costoUnitario,
        long stockResultante,
        String fecha,
        String motivo) {

    public static MovimientoInventarioDto de(MovimientoInventario movimiento, long stockResultante) {
        return new MovimientoInventarioDto(
                movimiento.getId(),
                movimiento.getVariante().getId(),
                movimiento.getTipo(),
                movimiento.getCantidad(),
                movimiento.getCostoUnitario(),
                stockResultante,
                String.valueOf(movimiento.getFecha()),
                movimiento.getMotivo());
    }
}
