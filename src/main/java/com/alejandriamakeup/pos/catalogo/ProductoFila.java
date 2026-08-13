package com.alejandriamakeup.pos.catalogo;

/**
 * Proyección plana de un producto, con los ids de marca y categoría en vez de las
 * entidades.
 *
 * <p>Es proyección y no entidad a propósito: cargar {@code Producto} traería
 * {@code @ManyToOne} perezosos, y leer el nombre de la marca dispararía una consulta
 * por fila. Con cincuenta variantes eso son cincuenta consultas que nadie ve hasta
 * que el catálogo tarda en abrir.
 */
public interface ProductoFila {

    Long getId();

    String getNombre();

    Long getMarcaId();

    Long getCategoriaId();

    String getDescripcion();

    boolean isActivo();
}
