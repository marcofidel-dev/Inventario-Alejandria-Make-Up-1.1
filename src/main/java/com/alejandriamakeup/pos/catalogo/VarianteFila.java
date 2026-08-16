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

    /**
     * Si el costo promedio está en cero.
     *
     * <p><strong>Es una bandera, no un costo.</strong> Dice que por esa variante nunca
     * entró mercancía valorada — o que todas las compras que la valoraban se anularon y
     * el replay dejó el promedio otra vez en cero —, sin publicar ningún importe. La
     * pantalla de venta la necesita para rechazar la variante <em>al agregarla al
     * carrito</em>, que es donde el rechazo no cuesta nada; hacerlo al cobrar deja a
     * quien atiende con el carrito lleno y una clienta enfrente.
     *
     * <p>No es lo mismo que {@code conHistorial}: una variante con movimientos cuyas
     * compras se anularon todas tiene historial y sigue sin costo. Tampoco es lo mismo
     * que {@code activo}: una variante inactiva sí se puede despachar.
     */
    boolean isSinCosto();

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
