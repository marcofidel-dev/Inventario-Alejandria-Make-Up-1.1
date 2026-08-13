package com.alejandriamakeup.pos.caja.dto;

import com.alejandriamakeup.pos.caja.TipoMovimientoCaja;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Un movimiento manual de caja.
 *
 * <p>El monto va <strong>positivo</strong> siempre: el signo lo pone el servidor
 * según el tipo. Y el concepto es obligatorio — un retiro sin explicación es un
 * descuadre esperando el fin de mes.
 */
public record RegistrarMovimientoPeticion(

        @NotNull(message = "El tipo de movimiento es obligatorio")
        TipoMovimientoCaja tipo,

        @NotNull(message = "El monto es obligatorio")
        @Positive(message = "El monto debe ser positivo: el signo lo pone el tipo de movimiento")
        Long monto,

        @NotBlank(message = "El concepto es obligatorio")
        @Size(max = 200, message = "El concepto no puede pasar de 200 caracteres")
        String concepto) {
}
