package com.alejandriamakeup.pos.catalogo;

import java.time.LocalDate;

/**
 * Proyección de una variante para el punto de venta.
 *
 * <p><strong>No tiene {@code costoPromedio}, y eso es el punto.</strong> No es que
 * se omita al mapear: es que el dato no llega hasta aquí. Un campo que existe es un
 * campo que alguien acaba pasando al DTO "para el debug"; uno que no existe no se
 * puede filtrar por descuido. Los costos viajan por
 * {@link VarianteCostoFila}, que solo usa el endpoint con permiso para verlos.
 */
public interface VarianteFila {

    Long getId();

    Long getProductoId();

    String getTono();

    String getTamano();

    String getCodigoBarras();

    long getPrecioVenta();

    int getStockMinimo();

    LocalDate getFechaVencimiento();

    Integer getPaoMeses();

    boolean isActivo();
}
