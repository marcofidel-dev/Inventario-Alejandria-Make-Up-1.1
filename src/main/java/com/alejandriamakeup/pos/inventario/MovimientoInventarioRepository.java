package com.alejandriamakeup.pos.inventario;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Ledger de inventario. Solo lectura y append: no hay método de borrado ni de
 * actualización porque la entidad es inmutable.
 */
public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, Long> {

    /**
     * El stock de una variante. Es una suma sobre el ledger, no la lectura de un
     * campo: esa es la invariante del sistema. Una variante sin movimientos da 0,
     * no null, gracias al {@code coalesce}.
     */
    @Query("select coalesce(sum(m.cantidad), 0) from MovimientoInventario m "
            + "where m.variante.id = :varianteId")
    long stockDe(@Param("varianteId") Long varianteId);

    /**
     * El stock de todas las variantes de un tirón, para la carga completa del
     * catálogo al front.
     *
     * <p>Ojo: una variante <strong>sin ningún movimiento no aparece</strong> en el
     * resultado — un {@code GROUP BY} no inventa filas. Quien consuma esto debe
     * tratar la ausencia como stock 0.
     */
    @Query("select m.variante.id as varianteId, coalesce(sum(m.cantidad), 0) as stock "
            + "from MovimientoInventario m group by m.variante.id")
    List<StockPorVariante> stockDeTodas();

    /** El histórico de una variante, en orden cronológico. */
    List<MovimientoInventario> findByVarianteIdOrderByFechaAsc(Long varianteId);

    /**
     * El histórico de una variante tal como lo necesita el recálculo del costo
     * promedio: cantidad, costo y el estado de la compra de la que viene, si viene
     * de alguna.
     *
     * <p><strong>El orden es {@code (fecha, id)} y no solo {@code fecha}.</strong>
     * {@code Fechas.ahora()} trunca a segundos porque el esquema guarda las fechas
     * como TEXT sin fracción, así que todos los movimientos de una misma recepción
     * comparten timestamp al segundo. Ordenar solo por fecha dejaría su orden
     * relativo a merced del plan de consulta, y el promedio ponderado <em>depende
     * del orden</em>: el mismo ledger daría números distintos entre corridas. El id
     * es monótono y desempata.
     *
     * <p>El {@code left join} es obligatorio y no una optimización: los movimientos
     * que no vienen de una compra —cargas iniciales, ajustes, ventas— tienen
     * {@code compra_id} nulo, y un join interno los dejaría fuera del cálculo.
     */
    @Query("select m.cantidad as cantidad, m.costoUnitario as costoUnitario, "
            + "c.estado as estadoCompra "
            + "from MovimientoInventario m left join m.compra c "
            + "where m.variante.id = :varianteId "
            + "order by m.fecha asc, m.id asc")
    List<MovimientoParaCosteo> historialParaCosteo(@Param("varianteId") Long varianteId);

    List<MovimientoInventario> findByVentaId(Long ventaId);

    List<MovimientoInventario> findByCompraId(Long compraId);

    List<MovimientoInventario> findByTipo(TipoMovimientoInventario tipo);

    /**
     * Si esta variante ya tiene su carga inicial. Una variante admite una sola: lo
     * que venga después es un ajuste, porque ya no se está declarando lo que había,
     * se está corrigiendo lo que se contó.
     */
    boolean existsByVarianteIdAndTipo(Long varianteId, TipoMovimientoInventario tipo);
}
