package com.alejandriamakeup.pos.ventas;

import java.time.LocalDateTime;

/**
 * Una línea de venta tal como la consumen las métricas: con el ingreso y el costo
 * <strong>ya calculados</strong>.
 *
 * <p>Esa es toda la razón de que exista este record en vez de pasear
 * {@link VentaItem}. El ingreso de una línea es {@code subtotal - descuento
 * prorrateado}, y olvidar el descuento no rompe nada: devuelve un margen más alto y
 * perfectamente creíble. Con datos reales del sistema, olvidarlo sube el margen del
 * 37,0% al 41,2%; contar además una venta anulada lo lleva al 41,5%. Ninguno de los
 * dos números se ve mal, y por eso nadie los revisa.
 *
 * <p>Aquí el subtotal crudo <strong>no existe como campo</strong>. Quien consuma
 * estas líneas no puede sumar el importe equivocado porque no lo tiene: la resta la
 * hizo la consulta, una sola vez, en
 * {@link VentaItemRepository#lineasVendidas}.
 *
 * <p>{@code descripcion} y {@code costo} salen de los campos congelados de
 * {@code venta_item}. Ni un join contra {@code variante}: el precio de mañana no
 * puede mover el margen de lo que se vendió hoy.
 */
public record LineaVendida(
        Long ventaId,
        Long varianteId,
        String descripcion,
        int cantidad,
        long ingreso,
        long costo,
        LocalDateTime fecha,
        MetodoPago metodoPago) {
}
