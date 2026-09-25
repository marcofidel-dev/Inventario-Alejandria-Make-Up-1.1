package com.alejandriamakeup.pos.compras.dto;

/**
 * El resultado de corregir una compra recibida: la que se anuló y el borrador nuevo,
 * precargado con las mismas líneas, listo para editar y volver a recibir.
 */
public record CorreccionCompraDto(CompraDto anulada, CompraDto borrador) {
}
