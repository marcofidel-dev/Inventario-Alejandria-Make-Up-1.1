package com.alejandriamakeup.pos.compras.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.alejandriamakeup.pos.compras.Compra;
import com.alejandriamakeup.pos.compras.CompraItem;
import com.alejandriamakeup.pos.compras.EstadoCompra;

/**
 * Una compra con sus líneas.
 *
 * <p>Las líneas llevan {@code varianteId} y no una descripción: el front ya tiene el
 * catálogo completo en memoria y resuelve el nombre desde ahí. Mandar además el
 * texto sería una segunda copia del mismo dato, y el día que alguien renombre un
 * producto las dos dirían cosas distintas.
 *
 * <p>Esto no contradice el congelado de {@code VentaItem}: en una venta el precio y
 * la descripción se congelan porque son la prueba de lo que se cobró. Una compra no
 * es un comprobante para nadie fuera de la tienda.
 */
public record CompraDto(
        Long id,
        String consecutivo,
        Long proveedorId,
        String numeroFactura,
        long total,
        EstadoCompra estado,
        LocalDateTime fecha,
        LocalDateTime fechaRecepcion,
        String notas,
        LocalDateTime fechaBaja,
        String motivoBaja,
        List<ItemDto> items) {

    public record ItemDto(
            Long id,
            Long varianteId,
            int cantidad,
            long costoUnitario,
            long subtotal) {

        public static ItemDto de(CompraItem item) {
            return new ItemDto(item.getId(), item.getVariante().getId(), item.getCantidad(),
                    item.getCostoUnitario(), item.getSubtotal());
        }
    }

    public static CompraDto de(Compra compra, List<CompraItem> items) {
        return new CompraDto(
                compra.getId(),
                compra.getConsecutivo(),
                compra.getProveedor().getId(),
                compra.getNumeroFactura(),
                compra.getTotal(),
                compra.getEstado(),
                compra.getFecha(),
                compra.getFechaRecepcion(),
                compra.getNotas(),
                compra.getFechaBaja(),
                compra.getMotivoBaja(),
                items.stream().map(ItemDto::de).toList());
    }
}
