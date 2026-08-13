package com.alejandriamakeup.pos.caja.dto;

/**
 * La base que se propone para abrir: lo que la última sesión cerrada dejó como
 * {@code base_siguiente}.
 *
 * <p>Este endpoint responde <strong>solo si no hay sesión abierta</strong>, y no es
 * un capricho: la base sugerida es exactamente el {@code base_inicial} con el que
 * se abrió la sesión en curso. Si siguiera respondiendo con una sesión ya abierta,
 * entregaría por la ventana el número que el cierre a ciegas oculta por la puerta.
 */
public record SugerenciaAperturaDto(
        long baseSugerida,
        String origen) {
}
