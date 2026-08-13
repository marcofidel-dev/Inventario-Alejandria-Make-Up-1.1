package com.alejandriamakeup.pos.catalogo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que el punto de venta necesita, en una sola llamada.
 *
 * <p>Se manda completo — activos e inactivos — porque la convención del proyecto es
 * cargar el catálogo entero al front y filtrar allí: en el mostrador la búsqueda
 * tiene que ser instantánea y una tienda de maquillaje cabe de sobra en memoria.
 *
 * <p><strong>Sin costos.</strong> Ni {@code costoPromedio} ni margen aparecen en
 * ningún nivel de esta estructura. Los costos salen por
 * {@code GET /api/v1/catalogo/costos}, que exige el permiso para verlos, y hay un
 * barrido automático que recorre todos los GET de la API con sesión de EMPLEADA
 * comprobando que nada se filtre.
 */
public record CatalogoDto(
        List<MarcaDto> marcas,
        List<CategoriaDto> categorias,
        List<ProductoDto> productos,
        List<VarianteDto> variantes) {

    public record MarcaDto(Long id, String nombre, boolean activo) {
    }

    public record CategoriaDto(Long id, String nombre, boolean activo) {
    }

    public record ProductoDto(
            Long id,
            String nombre,
            Long marcaId,
            Long categoriaId,
            String descripcion,
            boolean activo) {
    }

    /**
     * La variante con su stock.
     *
     * <p>{@code stock} es la suma del ledger, calculada en una consulta agrupada para
     * todas las variantes de una vez. Una variante sin ningún movimiento no aparece en
     * ese {@code GROUP BY}, y aquí sale con <strong>stock 0</strong>: ausente y cero no
     * son lo mismo para quien lee la lista.
     */
    public record VarianteDto(
            Long id,
            Long productoId,
            String tono,
            String tamano,
            String codigoBarras,
            long precioVenta,
            int stockMinimo,
            long stock,
            LocalDate fechaVencimiento,
            Integer paoMeses,
            boolean activo) {
    }
}
