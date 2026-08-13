package com.alejandriamakeup.pos.catalogo.dto;

/**
 * El resultado de activar o desactivar algo del catálogo, con sitio para una
 * advertencia.
 *
 * <p>La advertencia existe por un caso concreto: desactivar una variante que todavía
 * tiene unidades en el mostrador está permitido — "esto ya no lo vendo" es una
 * decisión legítima con existencias encima — pero no puede pasar en silencio. Esas
 * unidades siguen contando en el valor del inventario y dejan de ser vendibles, y
 * quien apretó el botón tiene que enterarse en ese momento y no cuando cuadre el mes.
 */
public record ResultadoActivacionDto(
        Long id,
        String nombre,
        boolean activo,
        String advertencia) {

    public static ResultadoActivacionDto sinAdvertencia(Long id, String nombre, boolean activo) {
        return new ResultadoActivacionDto(id, nombre, activo, null);
    }
}
