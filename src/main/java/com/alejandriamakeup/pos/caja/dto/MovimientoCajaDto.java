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
 *
 * <p>Con la sesión abierta, el front descarta el monto en el límite de la API
 * —{@code api/endpoints.js}— antes de que ninguna pantalla lo vea: él sí conoce la
 * base, porque él mismo la envió al abrir, y una lista que acumule los montos
 * reconstruye el esperado que el cierre a ciegas oculta.
 *
 * <p><strong>Ojo con {@link #de}:</strong> lee {@code usuario.nombre}, que es una
 * asociación LAZY. Con {@code open-in-view: false} hay que llamarla dentro de la
 * transacción — por eso {@code ServicioSesionCaja.movimientosDe()} devuelve DTOs y
 * no entidades.
 */
public record MovimientoCajaDto(
        Long id,
        TipoMovimientoCaja tipo,
        long monto,
        String concepto,
        String fecha,
        String usuario,
        Long ventaId) {

    public static MovimientoCajaDto de(MovimientoCaja movimiento) {
        return new MovimientoCajaDto(
                movimiento.getId(),
                movimiento.getTipo(),
                movimiento.getMonto(),
                movimiento.getConcepto(),
                String.valueOf(movimiento.getFecha()),
                movimiento.getUsuario().getNombre(),
                movimiento.getVenta() == null ? null : movimiento.getVenta().getId());
    }
}
