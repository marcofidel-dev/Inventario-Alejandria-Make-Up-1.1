package com.alejandriamakeup.pos.catalogo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VarianteRepository extends JpaRepository<Variante, Long> {

    Optional<Variante> findByCodigoBarras(String codigoBarras);

    List<Variante> findByProductoId(Long productoId);

    List<Variante> findByActivoTrue();

    /**
     * Variantes cuyo stock quedó por debajo de su mínimo.
     *
     * <p>La subconsulta correlacionada compara contra la suma del ledger, no
     * contra un campo. El {@code coalesce} hace que una variante sin ningún
     * movimiento cuente como stock 0, así que aparece si su mínimo es mayor que
     * cero — que es exactamente lo que se quiere de un producto nunca comprado.
     */
    @Query("select v from Variante v where v.activo = true and v.stockMinimo > "
            + "(select coalesce(sum(m.cantidad), 0) from MovimientoInventario m where m.variante = v)")
    List<Variante> bajoMinimo();

    /**
     * Todas las variantes en una sola consulta, sin costos y sin tocar asociaciones
     * perezosas. {@code v.producto.id} se resuelve contra la columna FK, no con un
     * join, así que esto es una consulta y no una por fila.
     */
    @Query("select v.id as id, v.producto.id as productoId, v.tono as tono, v.tamano as tamano, "
            + "v.codigoBarras as codigoBarras, v.precioVenta as precioVenta, "
            + "v.stockMinimo as stockMinimo, v.fechaVencimiento as fechaVencimiento, "
            + "v.paoMeses as paoMeses, v.activo as activo "
            + "from Variante v order by v.id")
    List<VarianteFila> filas();

    /** Con costos. Solo para quien tenga permiso de verlos. */
    @Query("select v.id as id, p.nombre as productoNombre, ma.nombre as marcaNombre, "
            + "v.tono as tono, v.tamano as tamano, v.precioVenta as precioVenta, "
            + "v.costoPromedio as costoPromedio, v.activo as activo "
            + "from Variante v join v.producto p join p.marca ma order by v.id")
    List<VarianteCostoFila> filasConCosto();

    /** Las variantes de un producto, por si hay que avisar al desactivarlo. */
    long countByProductoIdAndActivoTrue(Long productoId);
}
