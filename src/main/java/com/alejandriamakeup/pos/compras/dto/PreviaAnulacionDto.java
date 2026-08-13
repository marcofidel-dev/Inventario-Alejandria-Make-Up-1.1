package com.alejandriamakeup.pos.compras.dto;

import java.util.List;

/**
 * Qué le va a pasar al inventario si esta compra recibida se anula.
 *
 * <p>Existe por un caso que no es raro: la mercancía entró, se vendió una parte, y
 * después se descubre que la compra estaba mal. Al anularla se devuelven unidades
 * que ya no están, y el stock queda <strong>negativo</strong>.
 *
 * <p>Eso no se bloquea — si la compra nunca llegó, el negativo es información
 * verdadera: dice que el sistema vendió de más y hay que averiguar qué salió de la
 * vitrina. Pero tiene que decirse antes de confirmar y nombrando la variante, no
 * aparecer después como un número raro en el catálogo.
 *
 * <p>Informativa, igual que la previa de recepción: el servidor recalcula al
 * confirmar.
 */
public record PreviaAnulacionDto(
        Long compraId,
        String consecutivo,
        long total,
        List<LineaDto> lineas,
        boolean hayStockNegativo) {

    public record LineaDto(
            Long varianteId,
            int cantidadQueSeDevuelve,
            long stockActual,
            long stockResultante,
            boolean quedaNegativo) {
    }
}
