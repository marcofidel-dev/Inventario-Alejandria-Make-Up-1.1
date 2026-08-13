package com.alejandriamakeup.pos.caja.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * El cierre llega en una sola petición, con el conteo físico dentro.
 *
 * <p>Eso es lo que hace el cierre ciego imposible de invertir: no hay un paso
 * previo donde el sistema pueda mostrar el esperado, porque el conteo y el cierre
 * son la misma llamada. La respuesta a esta petición es la primera vez que
 * aparecen esperado, contado y diferencia.
 */
public record CerrarSesionPeticion(

        // El @Valid va en el argumento de tipo y no en la lista: sobre el
        // contenedor está deprecado y Hibernate Validator lo avisa en cada arranque.
        @NotEmpty(message = "El conteo por denominación es obligatorio")
        List<@Valid Denominacion> conteo,

        @NotNull(message = "El monto retirado es obligatorio")
        @PositiveOrZero(message = "El monto retirado no puede ser negativo")
        Long montoRetirado,

        @NotNull(message = "La base para el día siguiente es obligatoria")
        @PositiveOrZero(message = "La base siguiente no puede ser negativa")
        Long baseSiguiente,

        @Size(max = 500, message = "Las observaciones no pueden pasar de 500 caracteres")
        String observaciones) {

    /** Cuántas piezas de cada denominación se contaron. */
    public record Denominacion(

            @NotNull(message = "La denominación es obligatoria")
            @Positive(message = "La denominación debe ser positiva")
            Long denominacion,

            @NotNull(message = "La cantidad es obligatoria")
            @PositiveOrZero(message = "La cantidad no puede ser negativa")
            Integer cantidad) {
    }
}
