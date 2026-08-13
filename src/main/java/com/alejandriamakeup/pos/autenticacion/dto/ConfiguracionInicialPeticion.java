package com.alejandriamakeup.pos.autenticacion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Los datos de la usuaria administradora que se crea en el primer arranque. */
public record ConfiguracionInicialPeticion(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 60, message = "El nombre no puede pasar de 60 caracteres")
        String nombre,

        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "\\d{4,8}", message = "El PIN debe ser de 4 a 8 dígitos")
        String pin) {
}
