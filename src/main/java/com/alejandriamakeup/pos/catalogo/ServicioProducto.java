package com.alejandriamakeup.pos.catalogo;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Productos.
 *
 * <p>La clave natural es <strong>(marca, nombre)</strong> y no el nombre suelto: dos
 * marcas pueden vender cada una su "Labial mate" — en cosmética los nombres
 * genéricos abundan — pero Maybelline no puede tener dos. El índice
 * {@code ux_producto_marca_nombre} de V4 lo impone; aquí se comprueba antes para dar
 * el 409 con nombre y apellido.
 */
@Service
@Transactional(readOnly = true)
public class ServicioProducto {

    private static final Logger log = LoggerFactory.getLogger(ServicioProducto.class);

    private final ProductoRepository productoRepository;
    private final VarianteRepository varianteRepository;
    private final ServicioMarca servicioMarca;
    private final ServicioCategoria servicioCategoria;

    public ServicioProducto(ProductoRepository productoRepository,
                            VarianteRepository varianteRepository,
                            ServicioMarca servicioMarca,
                            ServicioCategoria servicioCategoria) {
        this.productoRepository = productoRepository;
        this.varianteRepository = varianteRepository;
        this.servicioMarca = servicioMarca;
        this.servicioCategoria = servicioCategoria;
    }

    /** Uso interno entre servicios y pruebas. Desde la API va {@link #crearDto}. */
    @Transactional
    public Producto crear(PeticionesCatalogo.Producto peticion) {
        Marca marca = servicioMarca.buscarEntidad(peticion.marcaId());
        Categoria categoria = servicioCategoria.buscarEntidad(peticion.categoriaId());
        exigirNombreLibreEnLaMarca(peticion.nombre(), marca, null);

        Producto producto = new Producto();
        producto.setNombre(peticion.nombre().strip());
        producto.setMarca(marca);
        producto.setCategoria(categoria);
        producto.setDescripcion(peticion.descripcion());
        producto.setActivo(true);
        producto.setFechaCreacion(Fechas.ahora());

        Producto guardado = productoRepository.save(producto);
        log.info("Producto creado: {} / {}", marca.getNombre(), guardado.getNombre());
        return guardado;
    }

    /** Uso interno. Desde la API va {@link #actualizarDto}. */
    @Transactional
    public Producto actualizar(long id, PeticionesCatalogo.Producto peticion) {
        Producto producto = buscarEntidad(id);
        Marca marca = servicioMarca.buscarEntidad(peticion.marcaId());
        Categoria categoria = servicioCategoria.buscarEntidad(peticion.categoriaId());
        exigirNombreLibreEnLaMarca(peticion.nombre(), marca, id);

        producto.setNombre(peticion.nombre().strip());
        producto.setMarca(marca);
        producto.setCategoria(categoria);
        producto.setDescripcion(peticion.descripcion());
        return productoRepository.save(producto);
    }

    @Transactional
    public ResultadoActivacionDto cambiarActivo(long id, boolean activo) {
        Producto producto = buscarEntidad(id);
        producto.setActivo(activo);
        productoRepository.save(producto);

        String advertencia = null;
        if (!activo) {
            long variantesActivas = varianteRepository.countByProductoIdAndActivoTrue(id);
            if (variantesActivas > 0) {
                advertencia = "El producto queda inactivo pero conserva " + variantesActivas
                        + " variante(s) activa(s). Desactívalas también si no se van a vender más.";
            }
        }

        log.info("Producto {} {}", producto.getNombre(), activo ? "reactivado" : "desactivado");
        return new ResultadoActivacionDto(producto.getId(), producto.getNombre(), activo, advertencia);
    }

    /**
     * Lo que responde el endpoint. El mapeo a DTO vive aquí y no en el controlador
     * porque las asociaciones son LAZY: mapearlas con la sesión ya cerrada lanza
     * {@code LazyInitializationException}, que en el mostrador se ve como un 500 al
     * guardar. Dentro de la transacción es un acceso normal.
     */
    @Transactional
    public CatalogoDto.ProductoDto crearDto(PeticionesCatalogo.Producto peticion) {
        return aDto(crear(peticion));
    }

    /** Ver {@link #crearDto}: el mapeo va dentro de la transacción. */
    @Transactional
    public CatalogoDto.ProductoDto actualizarDto(long id, PeticionesCatalogo.Producto peticion) {
        return aDto(actualizar(id, peticion));
    }

    private CatalogoDto.ProductoDto aDto(Producto producto) {
        return new CatalogoDto.ProductoDto(producto.getId(), producto.getNombre(),
                producto.getMarca().getId(), producto.getCategoria().getId(),
                producto.getDescripcion(), producto.isActivo());
    }

    /**
     * Uso interno entre servicios, nunca desde un controlador: devuelve la entidad
     * de persistencia, no un DTO. Exponerla en un endpoint arrastra a la API los
     * campos y las relaciones perezosas del modelo. Lo impide
     * {@code ControladoresNoDevuelvenEntidadesTest}.
     */
    public Producto buscarEntidad(long id) {
        return productoRepository.findById(id).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el producto " + id));
    }

    private void exigirNombreLibreEnLaMarca(String nombre, Marca marca, Long idQueSeExcluye) {
        String normalizado = NombreNormalizado.de(nombre);

        Optional<Producto> choque = productoRepository.findByMarcaId(marca.getId()).stream()
                .filter(existente -> !existente.getId().equals(idQueSeExcluye))
                .filter(existente -> NombreNormalizado.de(existente.getNombre()).equals(normalizado))
                .findFirst();

        if (choque.isPresent()) {
            throw ErrorDeAplicacion.conflicto("NOMBRE_DUPLICADO",
                    "La marca " + marca.getNombre() + " ya tiene el producto \""
                            + choque.get().getNombre() + "\", que es el mismo nombre salvo tildes "
                            + "o mayúsculas.");
        }
    }
}
