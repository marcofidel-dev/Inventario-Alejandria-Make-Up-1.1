package com.alejandriamakeup.pos.compras.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class PeticionesCompras {

    private PeticionesCompras() {
    }

    public record Proveedor(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
            String nombre,

            @Size(max = 30, message = "El NIT no puede pasar de 30 caracteres")
            String nit,

            @Size(max = 40, message = "El teléfono no puede pasar de 40 caracteres")
            String telefono,

            @Size(max = 120, message = "El contacto no puede pasar de 120 caracteres")
            String contacto,

            @Size(max = 300, message = "Las notas no pueden pasar de 300 caracteres")
            String notas) {
    }

    /**
     * Una compra en borrador, con sus líneas.
     *
     * <p><strong>No lleva total.</strong> No es un descuido: el total lo calcula el
     * servidor sumando las líneas. Aceptarlo del cliente sería dejar que la pantalla
     * decida cuánto se le debe al proveedor, y una pantalla con un redondeo distinto
     * —o alguien tocando la petición— dejaría una compra cuyo total no corresponde a
     * lo que dice tener dentro.
     */
    public record Compra(
            @NotNull(message = "El proveedor es obligatorio")
            Long proveedorId,

            @Size(max = 60, message = "El número de factura no puede pasar de 60 caracteres")
            String numeroFactura,

            @Size(max = 300, message = "Las notas no pueden pasar de 300 caracteres")
            String notas,

            @NotEmpty(message = "Hay que enviar al menos una línea")
            List<@Valid Linea> lineas) {

        public record Linea(
                @NotNull(message = "La variante es obligatoria")
                Long varianteId,

                @NotNull(message = "La cantidad es obligatoria")
                @Positive(message = "La cantidad debe ser positiva")
                Integer cantidad,

                @NotNull(message = "El costo unitario es obligatorio")
                @PositiveOrZero(message = "El costo unitario no puede ser negativo")
                Long costoUnitario) {
        }
    }

    /**
     * El motivo de un descarte o de una anulación.
     *
     * <p>Obligatorio en los dos casos, y no por simetría: una compra que desapareció
     * del inventario sin explicación es una pregunta que nadie va a poder responder
     * dentro de seis meses. El esquema lo exige también, con un CHECK.
     */
    public record Baja(
            @NotBlank(message = "El motivo es obligatorio")
            @Size(max = 300, message = "El motivo no puede pasar de 300 caracteres")
            String motivo) {
    }
}
