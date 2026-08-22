package com.alejandriamakeup.pos.catalogo.dto;

import com.alejandriamakeup.pos.dinero.Margen;

/**
 * Costo y margen de una variante. Solo sale por el endpoint que exige
 * {@code VER_COSTOS_Y_MARGENES}.
 *
 * <p>El margen se calcula y no se guarda: es {@code precio - costo}, derivable en
 * cualquier momento, y guardarlo sería otro dato que puede quedar desfasado. La
 * fórmula sale de {@link Margen}, la misma que usan la previa de recepción y las
 * métricas: escrita tres veces, tarde o temprano una de las tres se desvía.
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
        return new CostoVarianteDto(varianteId, descripcion(marca, producto, tono, tamano),
                precioVenta, costoPromedio,
                Margen.de(precioVenta, costoPromedio),
                Margen.porcentaje(precioVenta, costoPromedio));
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
