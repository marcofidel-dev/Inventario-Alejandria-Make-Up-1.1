package com.alejandriamakeup.pos.catalogo.dto;

/**
 * Costo y margen de una variante. Solo sale por el endpoint que exige
 * {@code VER_COSTOS_Y_MARGENES}.
 *
 * <p>El margen se calcula aquí y no se guarda: es {@code precio - costo}, derivable
 * en cualquier momento, y guardarlo sería otro dato que puede quedar desfasado.
 */
public record CostoVarianteDto(
        Long varianteId,
        String descripcion,
        long precioVenta,
        long costoPromedio,
        long margen,
        Integer margenPorcentaje) {

    public static CostoVarianteDto de(Long varianteId, String marca, String producto,
                                      String tono, String tamano,
                                      long precioVenta, long costoPromedio) {
        long margen = precioVenta - costoPromedio;
        Integer porcentaje = precioVenta > 0
                ? (int) Math.round(margen * 100.0 / precioVenta)
                : null;

        return new CostoVarianteDto(varianteId, descripcion(marca, producto, tono, tamano),
                precioVenta, costoPromedio, margen, porcentaje);
    }

    private static String descripcion(String marca, String producto, String tono, String tamano) {
        StringBuilder texto = new StringBuilder(marca).append(' ').append(producto);
        if (tono != null && !tono.isBlank()) {
            texto.append(" - ").append(tono);
        }
        if (tamano != null && !tamano.isBlank()) {
            texto.append(" (").append(tamano).append(')');
        }
        return texto.toString();
    }
}
