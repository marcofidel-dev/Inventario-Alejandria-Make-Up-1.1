package com.alejandriamakeup.pos.caja.dto;

import java.util.List;

import com.alejandriamakeup.pos.ventas.VentasPorMetodo;

/**
 * La respuesta del cierre, y el único sitio del sistema donde aparece el desglose
 * por método de pago.
 *
 * <p>Es un envoltorio y no un campo más dentro de {@link SesionDto.Cerrada}, por la
 * misma razón que {@code Cerrada} y {@code Abierta} son dos records y no uno con
 * campos nulos. Si el desglose viviera en {@code Cerrada}, el historial tendría que
 * calcularlo para cada una de las trescientas sesiones del año, o dejarlo en
 * {@code null} en todas partes menos aquí — y un campo que casi siempre va en null
 * es un campo que alguien llena "para el debug". Con el envoltorio, el tipo mismo
 * impone que solo la respuesta del cierre lo traiga.
 *
 * @param sesion la sesión ya cerrada, con esperado, contado y diferencia revelados
 * @param ventasPorMetodo qué se vendió y por qué medio; vacío si no hubo ventas
 */
public record ArqueoDto(
        SesionDto.Cerrada sesion,
        List<VentasPorMetodo> ventasPorMetodo) {
}
