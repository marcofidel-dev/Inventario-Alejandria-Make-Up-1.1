package com.alejandriamakeup.pos.ventas.dto;

import java.util.List;

import com.alejandriamakeup.pos.ventas.EstadoVenta;
import com.alejandriamakeup.pos.ventas.MetodoPago;

/**
 * Una venta tal como sale de la API.
 *
 * <p><strong>Sin costos ni márgenes, en ningún nivel y para ningún rol.</strong>
 * {@code venta_item} guarda el costo congelado —es justo el dato que la EMPLEADA no
 * puede ver— y aquí no hay campo donde ponerlo. Tampoco para la DUENA: un campo que
 * se muestra a un rol y se oculta al otro es exactamente la forma que toma la fuga
 * el día que alguien agrega una pantalla nueva. El margen es de la fase de métricas,
 * con su endpoint y su permiso propios.
 *
 * <p>{@code rutaRecibo} viaja en nulo hasta la Fase 9: ver
 * {@code GeneradorComprobante}.
 */
public record VentaDto(
        Long id,
        String uuid,
        String consecutivo,
        String fecha,
        Long sesionCajaId,
        String usuario,
        long subtotal,
        long descuento,
        long total,
        MetodoPago metodoPago,
        Long efectivoRecibido,
        Long cambio,
        EstadoVenta estado,
        String fechaAnulacion,
        String motivoAnulacion,
        String rutaRecibo,
        List<LineaDto> lineas,
        List<VarianteEnNegativoDto> variantesEnNegativo) {

    /**
     * Una línea con lo que se congeló al vender: precio, descripción y la parte del
     * descuento que le tocó. El costo congelado existe en la tabla y no sale de ahí.
     */
    public record LineaDto(
            Long varianteId,
            String descripcion,
            int cantidad,
            long precioUnitario,
            long descuentoProrrateado,
            long subtotal) {
    }

    /**
     * Las variantes que quedaron bajo cero después de esta venta.
     *
     * <p>Vender sin stock <strong>no se bloquea</strong>: con una clienta enfrente,
     * bloquear empuja a un ajuste improvisado que borra la evidencia del descuadre.
     * Pero tampoco puede pasar en silencio, y por eso viaja aquí: la pantalla avisa
     * nombrando la variante, y queda algo que averiguar en vez de un número raro que
     * aparece semanas después.
     */
    public record VarianteEnNegativoDto(Long varianteId, String descripcion, long stock) {
    }
}
