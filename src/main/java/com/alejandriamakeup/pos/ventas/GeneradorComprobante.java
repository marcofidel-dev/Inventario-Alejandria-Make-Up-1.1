package com.alejandriamakeup.pos.ventas;

import java.util.List;

/**
 * El comprobante de una venta.
 *
 * <p><strong>Declarada, sin implementación todavía.</strong> {@code PdfReciboLocal}
 * con PDFBox llega con el módulo de recibos; hoy {@code venta.ruta_recibo} queda en
 * nulo. Lo que ya existe es el sitio desde donde se va a reimprimir: el listado de
 * ventas del día. La interfaz existe desde ya porque es la costura por donde entra
 * mañana un adaptador de facturación electrónica DIAN sin tocar el punto de venta.
 *
 * <p>Se invoca <strong>después</strong> del commit de la venta, nunca dentro de la
 * transacción: la venta es la verdad y el comprobante es derivado. Si la generación
 * falla, se registra y se ofrece regenerar — lo que no puede pasar es que un PDF
 * roto tumbe un cobro ya hecho.
 *
 * <p>El documento dice "RECIBO" o "COMPROBANTE DE VENTA". Nunca "FACTURA": no está
 * validado por la DIAN.
 */
public interface GeneradorComprobante {

    /**
     * Genera el comprobante y devuelve su <strong>ruta relativa</strong>, del estilo
     * {@code recibos/2026/08/V-000123.pdf}. Relativa y no absoluta porque la carpeta
     * de datos se mueve con el equipo.
     */
    String generar(Venta venta, List<VentaItem> items);
}
