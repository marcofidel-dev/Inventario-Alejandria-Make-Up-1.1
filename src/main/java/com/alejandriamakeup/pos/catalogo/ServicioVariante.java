package com.alejandriamakeup.pos.catalogo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Variantes: la unidad real de todo el sistema.
 *
 * <p>Dos reglas que no están en el esquema y viven aquí:
 *
 * <ul>
 *   <li><strong>Una variante activa exige precio mayor que cero.</strong> El esquema
 *       admite {@code >= 0} porque una variante retirada puede quedar con precio 0,
 *       pero una activa con precio 0 se vendería gratis en el mostrador.
 *   <li><strong>Desactivar con stock se permite y se advierte.</strong> "Esto ya no lo
 *       vendo" es una decisión legítima con unidades encima; lo que no puede es pasar
 *       en silencio.
 * </ul>
 *
 * <p>{@code costoPromedio} no se toca nunca desde aquí: se deriva de la carga inicial
 * y de la recepción de compras. Los DTO de entrada ni tienen el campo.
 */
@Service
@Transactional(readOnly = true)
public class ServicioVariante {

    private static final Logger log = LoggerFactory.getLogger(ServicioVariante.class);

    private final VarianteRepository varianteRepository;
    private final ServicioProducto servicioProducto;
    private final MovimientoInventarioRepository movimientoRepository;

    public ServicioVariante(VarianteRepository varianteRepository,
                            ServicioProducto servicioProducto,
                            MovimientoInventarioRepository movimientoRepository) {
        this.varianteRepository = varianteRepository;
        this.servicioProducto = servicioProducto;
        this.movimientoRepository = movimientoRepository;
    }

    /** Uso interno entre servicios y pruebas. Desde la API va {@link #crearDto}. */
    @Transactional
    public Variante crear(PeticionesCatalogo.Variante peticion) {
        Producto producto = servicioProducto.buscarEntidad(peticion.productoId());
        exigirPrecioDeVarianteActiva(peticion.precioVenta(), true);

        Variante variante = new Variante();
        variante.setProducto(producto);
        aplicar(variante, peticion);
        variante.setActivo(true);
        variante.setFechaCreacion(Fechas.ahora());

        Variante guardada = varianteRepository.save(variante);
        log.info("Variante creada: producto {} tono {}", producto.getId(), guardada.getTono());
        return guardada;
    }

    /** Uso interno. Desde la API va {@link #actualizarDto}. */
    @Transactional
    public Variante actualizar(long id, PeticionesCatalogo.Variante peticion) {
        Variante variante = buscarEntidad(id);
        variante.setProducto(servicioProducto.buscarEntidad(peticion.productoId()));
        exigirPrecioDeVarianteActiva(peticion.precioVenta(), variante.isActivo());
        aplicar(variante, peticion);
        return varianteRepository.save(variante);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Variante variante = buscarEntidad(id);

        if (activo) {
            exigirPrecioDeVarianteActiva(variante.getPrecioVenta(), true);
        }

        variante.setActivo(activo);
        varianteRepository.save(variante);

        String advertencia = null;
        if (!activo) {
            long stock = movimientoRepository.stockDe(id);
            if (stock > 0) {
                advertencia = "Queda con " + stock + " unidad(es) en existencia que dejan de ser "
                        + "vendibles pero siguen contando en el valor del inventario. Si se van a "
                        + "dar de baja, hay que registrar la merma.";
            }
        }

        log.info("Variante {} {}", id, activo ? "reactivada" : "desactivada");
        return new ResultadoActivacionDto(variante.getId(), descripcion(variante), activo, advertencia);
    }

    /**
     * Lo que responde el endpoint. El mapeo a DTO vive aquí y no en el controlador
     * porque las asociaciones son LAZY: mapearlas con la sesión ya cerrada lanza
     * {@code LazyInitializationException}, que en el mostrador se ve como un 500 al
     * guardar. Dentro de la transacción es un acceso normal.
     */
    @Transactional
    public CatalogoDto.VarianteDto crearDto(PeticionesCatalogo.Variante peticion) {
        // Recién creada: cero movimientos, y por eso el catálogo todavía no la lista.
        // Aparece cuando se recibe la compra o se hace la carga inicial.
        return aDto(crear(peticion), 0L, false);
    }

    /** Ver {@link #crearDto}: el mapeo va dentro de la transacción. */
    @Transactional
    public CatalogoDto.VarianteDto actualizarDto(long id, PeticionesCatalogo.Variante peticion) {
        return aDto(actualizar(id, peticion), null, tieneMovimientos(id));
    }

    /**
     * El stock no se recalcula al escribir una variante: quien acaba de crearla sabe
     * que está en cero, y quien la edita ya tiene el catálogo cargado. Pedirlo aquí
     * sería una consulta por cada guardado para un dato que el cliente no usa.
     */
    private CatalogoDto.VarianteDto aDto(Variante variante, Long stock, boolean conHistorial) {
        return new CatalogoDto.VarianteDto(
                variante.getId(),
                variante.getProducto().getId(),
                // La misma función que congela la descripción al vender y que imprime el
                // recibo: ver Descripcion.
                Descripcion.de(variante.getProducto().getMarca().getNombre(),
                        variante.getProducto().getNombre(),
                        variante.getTono(),
                        variante.getTamano()),
                variante.getTono(),
                variante.getTamano(),
                variante.getCodigoBarras(),
                variante.getPrecioVenta(),
                variante.getStockMinimo(),
                stock == null ? 0L : stock,
                conHistorial,
                // La bandera, no el importe. Una variante recién creada desde el flujo
                // de compra sale de aquí con costo cero — que es la verdad: la
                // mercancía todavía no llegó.
                variante.getCostoPromedio() == 0,
                variante.getFechaVencimiento(),
                variante.getPaoMeses(),
                variante.isActivo());
    }

    /**
     * Si la variante tiene algún movimiento. Lo pregunta la respuesta de una edición
     * para no afirmar lo que no sabe: una variante recién creada no tiene ninguno —el
     * catálogo no la lista— y una que ya recibió mercancía sí.
     */
    public boolean tieneMovimientos(long id) {
        return movimientoRepository.existsByVarianteId(id);
    }

    /**
     * Uso interno entre servicios, nunca desde un controlador: devuelve la entidad
     * de persistencia, no un DTO. Exponerla en un endpoint arrastra a la API los
     * campos y las relaciones perezosas del modelo. Lo impide
     * {@code ControladoresNoDevuelvenEntidadesTest}.
     */
    public Variante buscarEntidad(long id) {
        return varianteRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la variante " + id));
    }

    /** Todo salvo el producto, el estado y el costo promedio. */
    private void aplicar(Variante variante, PeticionesCatalogo.Variante peticion) {
        variante.setTono(vacioComoNulo(peticion.tono()));
        variante.setTamano(vacioComoNulo(peticion.tamano()));
        variante.setCodigoBarras(vacioComoNulo(peticion.codigoBarras()));
        variante.setPrecioVenta(peticion.precioVenta());
        variante.setStockMinimo(peticion.stockMinimo() == null ? 0 : peticion.stockMinimo());
        variante.setFechaVencimiento(peticion.fechaVencimiento());
        variante.setPaoMeses(peticion.paoMeses());
    }

    private void exigirPrecioDeVarianteActiva(long precioVenta, boolean activa) {
        if (activa && precioVenta <= 0) {
            throw ErrorDeAplicacion.peticionInvalida(
                    "Una variante activa necesita un precio de venta mayor que cero: con precio 0 "
                            + "se vendería gratis en el mostrador.");
        }
    }

    /**
     * Cadena vacía y nulo son lo mismo aquí, y conviene que lo sean: el índice único
     * de la combinación aplana el nulo a cadena vacía, así que guardar {@code ""} en
     * vez de {@code null} crearía dos variantes que la base considera la misma.
     */
    private String vacioComoNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private String descripcion(Variante variante) {
        StringBuilder texto = new StringBuilder();
        if (variante.getTono() != null) {
            texto.append(variante.getTono());
        }
        if (variante.getTamano() != null) {
            texto.append(texto.isEmpty() ? "" : " ").append(variante.getTamano());
        }
        return texto.isEmpty() ? "variante " + variante.getId() : texto.toString();
    }
}
