package com.alejandriamakeup.pos.catalogo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.CostoVarianteDto;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.StockPorVariante;

/**
 * El catálogo completo con stock, en una llamada y en un número fijo de consultas.
 *
 * <p><strong>Cinco consultas, y cinco se quedan.</strong> Marcas, categorías,
 * productos, variantes y una agrupada de stock. No cuatro más una por variante: eso
 * es lo que pasa si se cargan entidades y se leen sus asociaciones perezosas al
 * mapear, y es invisible hasta que la tienda tiene inventario de verdad y el
 * catálogo tarda tres segundos en abrir. De ahí que productos y variantes se lean
 * como proyecciones planas con ids.
 *
 * <p>Hay un test que cuenta las sentencias emitidas con 5 variantes y con 50 y exige
 * el mismo número. No mide velocidad, mide que el número no dependa del tamaño.
 */
@Service
@Transactional(readOnly = true)
public class ServicioCatalogoCompleto {

    private final MarcaRepository marcaRepository;
    private final CategoriaRepository categoriaRepository;
    private final ProductoRepository productoRepository;
    private final VarianteRepository varianteRepository;
    private final MovimientoInventarioRepository movimientoRepository;

    public ServicioCatalogoCompleto(MarcaRepository marcaRepository,
                                    CategoriaRepository categoriaRepository,
                                    ProductoRepository productoRepository,
                                    VarianteRepository varianteRepository,
                                    MovimientoInventarioRepository movimientoRepository) {
        this.marcaRepository = marcaRepository;
        this.categoriaRepository = categoriaRepository;
        this.productoRepository = productoRepository;
        this.varianteRepository = varianteRepository;
        this.movimientoRepository = movimientoRepository;
    }

    public CatalogoDto completo() {
        List<CatalogoDto.MarcaDto> marcas = marcaRepository.findAll().stream()
                .map(m -> new CatalogoDto.MarcaDto(m.getId(), m.getNombre(), m.isActivo()))
                .toList();

        List<CatalogoDto.CategoriaDto> categorias = categoriaRepository.findAll().stream()
                .map(c -> new CatalogoDto.CategoriaDto(c.getId(), c.getNombre(), c.isActivo()))
                .toList();

        List<CatalogoDto.ProductoDto> productos = productoRepository.filas().stream()
                .map(p -> new CatalogoDto.ProductoDto(p.getId(), p.getNombre(), p.getMarcaId(),
                        p.getCategoriaId(), p.getDescripcion(), p.isActivo()))
                .toList();

        Map<Long, Long> stockPorVariante = stockPorVariante();

        List<CatalogoDto.VarianteDto> variantes = varianteRepository.filas().stream()
                .map(v -> new CatalogoDto.VarianteDto(
                        v.getId(),
                        v.getProductoId(),
                        v.getTono(),
                        v.getTamano(),
                        v.getCodigoBarras(),
                        v.getPrecioVenta(),
                        v.getStockMinimo(),
                        // Una variante sin movimientos no sale del GROUP BY. Su stock es
                        // cero, pero lo que importa es la bandera: agotada y nunca
                        // recibida dan el mismo número y no son lo mismo. El front lista
                        // solo las que tienen historial.
                        stockPorVariante.getOrDefault(v.getId(), 0L),
                        stockPorVariante.containsKey(v.getId()),
                        v.getFechaVencimiento(),
                        v.getPaoMeses(),
                        v.isActivo()))
                .toList();

        return new CatalogoDto(marcas, categorias, productos, variantes);
    }

    /** Costos y márgenes. Solo lo llama el endpoint que exige el permiso. */
    public List<CostoVarianteDto> costos() {
        return varianteRepository.filasConCosto().stream()
                .map(f -> CostoVarianteDto.de(f.getId(), f.getMarcaNombre(), f.getProductoNombre(),
                        f.getTono(), f.getTamano(), f.getPrecioVenta(), f.getCostoPromedio()))
                .toList();
    }

    /** Una sola consulta agrupada sobre el ledger, no una por variante. */
    private Map<Long, Long> stockPorVariante() {
        Map<Long, Long> porVariante = new HashMap<>();
        for (StockPorVariante fila : movimientoRepository.stockDeTodas()) {
            porVariante.put(fila.getVarianteId(), fila.getStock());
        }
        return porVariante;
    }
}
