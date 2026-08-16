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

        // La descripción se arma aquí, EN MEMORIA, con lo que ya se leyó: no es una
        // consulta más ni una asociación perezosa por variante. Las cinco consultas
        // siguen siendo cinco, que es lo que vigila CatalogoSinNMasUnoTest.
        Map<Long, String> nombreDeMarca = new HashMap<>();
        marcas.forEach(m -> nombreDeMarca.put(m.id(), m.nombre()));
        Map<Long, CatalogoDto.ProductoDto> porProducto = new HashMap<>();
        productos.forEach(p -> porProducto.put(p.id(), p));

        List<CatalogoDto.VarianteDto> variantes = varianteRepository.filas().stream()
                .map(v -> new CatalogoDto.VarianteDto(
                        v.getId(),
                        v.getProductoId(),
                        descripcionDe(v, porProducto, nombreDeMarca),
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
                        // No se puede vender lo que no tiene costo, y la pantalla lo
                        // sabe antes de agregarlo al carrito. La bandera viaja; el
                        // importe no llega ni a la proyección.
                        v.isSinCosto(),
                        v.getFechaVencimiento(),
                        v.getPaoMeses(),
                        v.isActivo()))
                .toList();

        return new CatalogoDto(marcas, categorias, productos, variantes);
    }

    /**
     * La misma función que congela la descripción al vender y que imprime el recibo.
     * Que sea la misma es el punto: ver {@link Descripcion}.
     */
    private String descripcionDe(VarianteFila variante,
                                 Map<Long, CatalogoDto.ProductoDto> porProducto,
                                 Map<Long, String> nombreDeMarca) {
        CatalogoDto.ProductoDto producto = porProducto.get(variante.getProductoId());
        String marca = producto == null ? null : nombreDeMarca.get(producto.marcaId());
        return Descripcion.de(marca,
                producto == null ? null : producto.nombre(),
                variante.getTono(),
                variante.getTamano());
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
