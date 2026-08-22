package com.alejandriamakeup.pos.catalogo;

import java.time.LocalDate;

/**
 * Todo lo que las métricas necesitan saber de una variante, en una consulta.
 *
 * <p>Separada de {@link VarianteCostoFila} porque trae tres campos que aquella no
 * necesita —mínimo, vencimiento y PAO— y porque son consumidores distintos: aquella
 * alimenta el listado de costos del catálogo, esta el panel. Compartir una sola
 * proyección obligaría a que cada nueva columna de una la arrastre la otra.
 *
 * <p>Lleva costo, así que todo lo que se sirva a partir de ella exige permiso. El
 * módulo entero es de la DUENA.
 */
public interface VarianteMetricaFila {

    Long getId();

    String getMarcaNombre();

    String getProductoNombre();

    String getTono();

    String getTamano();

    long getPrecioVenta();

    long getCostoPromedio();

    int getStockMinimo();

    LocalDate getFechaVencimiento();

    Integer getPaoMeses();

    boolean isActivo();
}
