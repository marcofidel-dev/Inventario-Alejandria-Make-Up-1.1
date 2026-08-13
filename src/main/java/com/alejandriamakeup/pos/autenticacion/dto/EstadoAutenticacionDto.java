package com.alejandriamakeup.pos.autenticacion.dto;

/**
 * Lo que el front necesita para decidir qué pantalla mostrar antes de tener
 * sesión. Deliberadamente no dice si un nombre de usuario existe.
 */
public record EstadoAutenticacionDto(
        boolean requiereConfiguracionInicial,
        boolean autenticado) {
}
