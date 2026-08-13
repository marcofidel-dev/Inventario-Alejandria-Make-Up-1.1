package com.alejandriamakeup.pos.caja.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AbrirSesionPeticion(

        @NotNull(message = "La base inicial es obligatoria")
        @PositiveOrZero(message = "La base inicial no puede ser negativa")
        Long baseInicial,

        @Size(max = 500, message = "Las observaciones no pueden pasar de 500 caracteres")
        String observaciones) {
}
