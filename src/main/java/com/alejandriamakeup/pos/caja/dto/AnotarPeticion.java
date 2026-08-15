package com.alejandriamakeup.pos.caja.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Una nota que se agrega a una sesión de caja.
 *
 * <p>No hay campo para la fecha ni para el autor: los pone el servidor. Una nota
 * que se pudiera antedatar o firmar con otro nombre no explicaría nada, serviría
 * para tapar.
 */
public record AnotarPeticion(

        @NotBlank(message = "La nota no puede estar vacía")
        @Size(max = 500, message = "La nota no puede pasar de 500 caracteres")
        String texto) {
}
