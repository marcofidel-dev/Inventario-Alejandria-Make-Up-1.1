package com.alejandriamakeup.pos.caja.dto;

import com.alejandriamakeup.pos.caja.MovimientoCaja;
import com.alejandriamakeup.pos.caja.TipoMovimientoCaja;

/**
 * Un movimiento del cajón.
 *
 * <p>El monto sí se muestra, con su signo: la cajera necesita verificar lo que
 * registró. Lo que nunca acompaña a una lista de movimientos de sesión abierta es
 * un <strong>total</strong>, porque sumado a la base inicial sería el efectivo
 * esperado.
 */
public record MovimientoCajaDto(
        Long id,
        TipoMovimientoCaja tipo,
        long monto,
        String concepto,
        String fecha,
        Long ventaId) {

    public static MovimientoCajaDto de(MovimientoCaja movimiento) {
        return new MovimientoCajaDto(
                movimiento.getId(),
                movimiento.getTipo(),
                movimiento.getMonto(),
                movimiento.getConcepto(),
                String.valueOf(movimiento.getFecha()),
                movimiento.getVenta() == null ? null : movimiento.getVenta().getId());
    }
}
