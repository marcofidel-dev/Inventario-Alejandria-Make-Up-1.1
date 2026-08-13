package com.alejandriamakeup.pos.inventario.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class PeticionesInventario {

    private PeticionesInventario() {
    }

    /**
     * La carga inicial llega por lotes porque así se va a usar: sentarse una vez con
     * el inventario contado y meterlo entero, no variante por variante.
     */
    public record CargaInicial(
            @NotEmpty(message = "Hay que enviar al menos una línea")
            List<@Valid Linea> lineas) {

        public record Linea(
                @NotNull(message = "La variante es obligatoria")
                Long varianteId,

                @NotNull(message = "La cantidad es obligatoria")
                @Positive(message = "La cantidad debe ser positiva: una carga inicial de cero no dice nada")
                Integer cantidad,

                @NotNull(message = "El costo unitario es obligatorio")
                @PositiveOrZero(message = "El costo unitario no puede ser negativo")
                Long costoUnitario) {
        }
    }

    /**
     * Un ajuste. El motivo es obligatorio: un ajuste sin explicación es un descuadre
     * anónimo que nadie va a poder reconstruir en tres meses.
     */
    public record Ajuste(
            @NotNull(message = "La variante es obligatoria")
            Long varianteId,

            @NotNull(message = "La cantidad es obligatoria")
            Integer cantidad,

            @NotBlank(message = "El motivo es obligatorio")
            @Size(max = 300, message = "El motivo no puede pasar de 300 caracteres")
            String motivo) {
    }
}
