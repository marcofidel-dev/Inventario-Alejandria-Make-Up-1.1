package com.alejandriamakeup.pos.autenticacion.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginPeticion(

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El PIN es obligatorio")
        String pin) {
}
