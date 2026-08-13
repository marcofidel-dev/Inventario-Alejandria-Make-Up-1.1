package com.alejandriamakeup.pos.catalogo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    /**
     * Todos los productos en una consulta, con los ids de marca y categoría en vez
     * de las entidades. Ver {@link ProductoFila} para por qué es proyección.
     */
    @Query("select p.id as id, p.nombre as nombre, p.marca.id as marcaId, "
            + "p.categoria.id as categoriaId, p.descripcion as descripcion, p.activo as activo "
            + "from Producto p order by p.id")
    List<ProductoFila> filas();

    List<Producto> findByActivoTrue();

    List<Producto> findByMarcaId(Long marcaId);

    List<Producto> findByCategoriaId(Long categoriaId);

    List<Producto> findByNombreContainingIgnoreCase(String fragmento);
}
