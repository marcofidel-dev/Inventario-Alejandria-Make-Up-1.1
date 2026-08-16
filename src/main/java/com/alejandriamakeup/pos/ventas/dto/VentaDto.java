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
 * <p>{@code rutaRecibo} viaja en nulo hasta que exista el generador de recibos: ver
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
     * La misma venta con la ruta de su recibo puesta.
     *
     * <p>Existe para que el cobro no tenga que releer la venta entera solo por este
     * campo. El comprobante se genera después del commit, cuando el DTO de la
     * respuesta ya está armado; sin esto habría que volver a consultar la venta, sus
     * líneas y el stock de cada variante —eso es lo que arma {@code variantesEnNegativo}—
     * en el camino del cobro, que es el más sensible del sistema, para enterarse de un
     * dato que el generador acaba de devolver.
     */
    public VentaDto conRutaRecibo(String ruta) {
        return new VentaDto(id, uuid, consecutivo, fecha, sesionCajaId, usuario, subtotal,
                descuento, total, metodoPago, efectivoRecibido, cambio, estado, fechaAnulacion,
                motivoAnulacion, ruta, lineas, variantesEnNegativo);
    }

    /**
     * Una venta en el listado del día: lo justo para encontrarla.
     *
     * <p><strong>No es un {@link VentaDto} recortado, y por eso existe.</strong> Armar
     * el DTO completo por fila cargaría los items de cada venta y, dentro de
     * {@code variantesEnNegativo}, una consulta de stock por variante — de golpe son
     * cientos de consultas sobre un pool de una sola conexión, para pintar una tabla
     * que no muestra ni las líneas. Y el aviso de negativos no significa nada aquí: se
     * escribió para el instante del cobro, no para una lista de ayer.
     *
     * <p>Sin costos ni márgenes, como todo el módulo.
     *
     * <p>{@code rutaRecibo} viaja aquí porque es lo que decide qué ofrece cada fila:
     * ver el comprobante, o generarlo si faltó. Sin el campo, el listado tendría que
     * pedir la venta completa por fila para saberlo.
     */
    public record Resumen(
            Long id,
            String consecutivo,
            String fecha,
            long total,
            MetodoPago metodoPago,
            EstadoVenta estado,
            String usuario,
            String motivoAnulacion,
            String rutaRecibo) {
    }

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
