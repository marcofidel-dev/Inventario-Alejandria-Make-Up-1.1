package com.alejandriamakeup.pos.ventas;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Los items son inmutables: solo lectura y append. */
public interface VentaItemRepository extends JpaRepository<VentaItem, Long> {

    List<VentaItem> findByVentaId(Long ventaId);

    List<VentaItem> findByVarianteId(Long varianteId);

    /**
     * <strong>La única fuente de líneas vendidas del sistema.</strong> Todas las
     * métricas de venta —totales, comparativos, margen, más vendidos, ventas por
     * método de pago, rotación— salen de aquí y no emiten SQL propio.
     *
     * <p>Es una sola consulta a propósito, y no nueve. Los dos filtros que hacen
     * verdadero el número viven en este único {@code where}:
     *
     * <ul>
     *   <li><strong>Solo {@code COMPLETADA}.</strong> Una anulada no se vendió.
     *   <li><strong>Ingreso = {@code subtotal - descuento prorrateado}.</strong> El
     *       descuento se congeló por línea justamente para esto.
     * </ul>
     *
     * Escritos nueve veces, tarde o temprano una consulta se olvida de uno de los
     * dos y devuelve un margen inflado que nadie va a auditar, porque parece
     * razonable. Escritos una vez, el error no tiene dónde entrar: lo que sale de
     * aquí ya viene neteado, y {@link LineaVendida} ni siquiera lleva el subtotal
     * crudo.
     *
     * <p>El rango es <strong>{@code [desde, hasta)}</strong> — cerrado por abajo y
     * abierto por arriba. Así el llamador pasa el comienzo del día siguiente y no
     * tiene que inventarse un {@code 23:59:59} que dejaría fuera una venta hecha en
     * el último segundo. Funciona porque las fechas se guardan como TEXT ISO-8601 de
     * ancho fijo: comparar ese texto equivale a comparar cronológicamente.
     */
    @Query("""
            select new com.alejandriamakeup.pos.ventas.LineaVendida(
                       v.id, vi.variante.id, vi.descripcionCongelada, vi.cantidad,
                       vi.subtotal - vi.descuentoProrrateado,
                       vi.costoUnitarioCongelado * vi.cantidad,
                       v.fecha, v.metodoPago)
            from VentaItem vi join vi.venta v
            where v.estado = com.alejandriamakeup.pos.ventas.EstadoVenta.COMPLETADA
              and v.fecha >= :desde
              and v.fecha < :hasta
            order by v.fecha asc, vi.id asc
            """)
    List<LineaVendida> lineasVendidas(@Param("desde") LocalDateTime desde,
                                      @Param("hasta") LocalDateTime hasta);
}
