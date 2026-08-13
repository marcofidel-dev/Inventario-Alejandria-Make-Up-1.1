package com.alejandriamakeup.pos.compras.dto;

import java.util.List;

/**
 * Qué le va a pasar al inventario si esta compra se recibe.
 *
 * <p><strong>Es informativa y nada más.</strong> Los números de aquí no vuelven al
 * servidor: la confirmación manda solo el id de la compra y el servidor recalcula
 * con el stock de ese instante. Si alguien vendió mientras la pantalla estaba
 * abierta, esta previa quedó vieja — y quien tiene razón es el ledger, no la
 * pantalla.
 *
 * <p>Las banderas vienen calculadas del servidor, no deducidas en el front. Es el
 * mismo criterio que "stock bajo": si la pantalla reimplementara el umbral, un día
 * el aviso y la regla dirían cosas distintas y nadie sabría cuál creer.
 */
public record PreviaRecepcionDto(
        Long compraId,
        String consecutivo,
        long total,
        List<LineaDto> lineas,
        boolean hayAdvertencias) {

    /**
     * @param costoSuperaPrecio la mercancía costaría más de lo que se vende: cada
     *        unidad vendida pierde plata
     * @param margenBajo el margen queda por debajo del umbral del sistema
     * @param margenPorcentaje redondeado, solo para mostrar. Las banderas no salen de
     *        este número: se calculan con aritmética entera exacta, porque un margen
     *        de 19,6% redondeado a 20 no puede dejar de encender el aviso
     */
    public record LineaDto(
            Long varianteId,
            int cantidad,
            long stockActual,
            long stockResultante,
            long costoUnitario,
            long costoPromedioActual,
            long costoPromedioResultante,
            long precioVenta,
            int margenPorcentaje,
            boolean costoSuperaPrecio,
            boolean margenBajo) {

        public boolean tieneAdvertencia() {
            return costoSuperaPrecio || margenBajo;
        }
    }
}
