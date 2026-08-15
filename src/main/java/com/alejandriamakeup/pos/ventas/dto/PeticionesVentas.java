package com.alejandriamakeup.pos.ventas.dto;

import java.util.List;

import com.alejandriamakeup.pos.ventas.MetodoPago;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class PeticionesVentas {

    private PeticionesVentas() {
    }

    /**
     * Un cobro.
     *
     * <p><strong>No lleva total ni cambio.</strong> Los dos los calcula el servidor:
     * el total sumando las líneas a su precio del momento, el cambio restando el
     * total de lo que se recibió. Aceptarlos del cliente sería dejar que la pantalla
     * decida cuánto se cobró y cuánto se devolvió.
     *
     * <p>{@code descuento} es un <strong>monto en pesos sobre el total</strong>, no un
     * porcentaje ni un valor por línea. El reparto proporcional entre las líneas lo
     * hace el servidor y lo congela en cada {@code venta_item}.
     */
    public record Venta(
            @NotBlank(message = "El uuid es obligatorio")
            @Size(max = 60, message = "El uuid no puede pasar de 60 caracteres")
            String uuid,

            @NotNull(message = "El método de pago es obligatorio")
            MetodoPago metodoPago,

            @PositiveOrZero(message = "El descuento no puede ser negativo")
            Long descuento,

            // Solo se lee si el método es EFECTIVO. Con cualquier otro se ignora:
            // una venta por datáfono no recibe billetes.
            @PositiveOrZero(message = "El efectivo recibido no puede ser negativo")
            Long efectivoRecibido,

            // Al menos una. Una venta sin líneas quemaría un consecutivo y dejaría un
            // documento de total 0 sin nada dentro: un hueco en la numeración que
            // nadie sabría explicar, que es justo lo que la tabla consecutivo existe
            // para evitar.
            @NotEmpty(message = "Hay que enviar al menos una línea")
            List<@Valid Linea> lineas) {

        public record Linea(
                @NotNull(message = "La variante es obligatoria")
                Long varianteId,

                @NotNull(message = "La cantidad es obligatoria")
                @Positive(message = "La cantidad debe ser positiva")
                Integer cantidad) {
        }
    }

    /**
     * El motivo de una anulación. Obligatorio: una venta que desapareció del
     * inventario y del cajón sin explicación es una pregunta que nadie va a poder
     * responder dentro de seis meses.
     */
    public record Anulacion(
            @NotBlank(message = "El motivo es obligatorio")
            @Size(max = 300, message = "El motivo no puede pasar de 300 caracteres")
            String motivo) {
    }
}
