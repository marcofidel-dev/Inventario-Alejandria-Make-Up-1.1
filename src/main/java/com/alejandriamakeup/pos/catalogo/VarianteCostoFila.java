package com.alejandriamakeup.pos.catalogo;

/**
 * Proyección con costos, para el único endpoint que puede mostrarlos.
 *
 * <p>Separada de {@link VarianteFila} en vez de ser un superconjunto suyo: así el
 * camino del punto de venta no tiene manera de acarrear un costo, ni siquiera por
 * accidente.
 */
public interface VarianteCostoFila {

    Long getId();

    String getProductoNombre();

    String getMarcaNombre();

    String getTono();

    String getTamano();

    long getPrecioVenta();

    long getCostoPromedio();

    boolean isActivo();
}
