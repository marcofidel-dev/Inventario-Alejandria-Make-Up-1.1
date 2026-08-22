package com.alejandriamakeup.pos.catalogo;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.CostoVarianteDto;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;

import jakarta.validation.Valid;

/**
 * La API del catálogo.
 *
 * <p><strong>No hay verbo DELETE en ninguna ruta.</strong> Nada se borra: se
 * desactiva, con un {@code POST} a {@code .../desactivacion}. Las FK son RESTRICT, así
 * que un borrado físico fallaría igual — pero el punto es que ni se ofrezca la
 * posibilidad, porque un producto borrado se lleva consigo la historia de ventas que
 * lo referencia.
 *
 * <p>Quién puede llamar a qué no está aquí: vive en {@code ReglasDeAcceso}.
 */
@RestController
@RequestMapping("/api/v1/catalogo")
public class CatalogoController {

    private final ServicioMarca servicioMarca;
    private final ServicioCategoria servicioCategoria;
    private final ServicioProducto servicioProducto;
    private final ServicioVariante servicioVariante;
    private final ServicioCatalogoCompleto servicioCatalogo;

    public CatalogoController(ServicioMarca servicioMarca,
                              ServicioCategoria servicioCategoria,
                              ServicioProducto servicioProducto,
                              ServicioVariante servicioVariante,
                              ServicioCatalogoCompleto servicioCatalogo) {
        this.servicioMarca = servicioMarca;
        this.servicioCategoria = servicioCategoria;
        this.servicioProducto = servicioProducto;
        this.servicioVariante = servicioVariante;
        this.servicioCatalogo = servicioCatalogo;
    }

    // ------------------------------------------------------------------ lectura

    /** Todo lo que el punto de venta necesita, con stock y sin costos. */
    @GetMapping
    public CatalogoDto completo() {
        return servicioCatalogo.completo();
    }

    /** Costos y márgenes. La única puerta por la que salen. */
    @GetMapping("/costos")
    public List<CostoVarianteDto> costos() {
        return servicioCatalogo.costos();
    }

    // ------------------------------------------------------------------- marcas

    @PostMapping("/marcas")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogoDto.MarcaDto crearMarca(@Valid @RequestBody PeticionesCatalogo.Marca peticion) {
        return servicioMarca.crearDto(peticion.nombre());
    }

    @PutMapping("/marcas/{id}")
    public CatalogoDto.MarcaDto renombrarMarca(@PathVariable long id,
                                               @Valid @RequestBody PeticionesCatalogo.Marca peticion) {
        return servicioMarca.renombrarDto(id, peticion.nombre());
    }

    @PostMapping("/marcas/{id}/desactivacion")
    public ResultadoActivacionDto desactivarMarca(@PathVariable long id) {
        return servicioMarca.cambiarActivo(id, false);
    }

    @PostMapping("/marcas/{id}/reactivacion")
    public ResultadoActivacionDto reactivarMarca(@PathVariable long id) {
        return servicioMarca.cambiarActivo(id, true);
    }

    // --------------------------------------------------------------- categorías

    @PostMapping("/categorias")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogoDto.CategoriaDto crearCategoria(
            @Valid @RequestBody PeticionesCatalogo.Categoria peticion) {
        return servicioCategoria.crearDto(peticion.nombre());
    }

    @PutMapping("/categorias/{id}")
    public CatalogoDto.CategoriaDto renombrarCategoria(
            @PathVariable long id, @Valid @RequestBody PeticionesCatalogo.Categoria peticion) {
        return servicioCategoria.renombrarDto(id, peticion.nombre());
    }

    @PostMapping("/categorias/{id}/desactivacion")
    public ResultadoActivacionDto desactivarCategoria(@PathVariable long id) {
        return servicioCategoria.cambiarActivo(id, false);
    }

    @PostMapping("/categorias/{id}/reactivacion")
    public ResultadoActivacionDto reactivarCategoria(@PathVariable long id) {
        return servicioCategoria.cambiarActivo(id, true);
    }

    // ---------------------------------------------------------------- productos

    @PostMapping("/productos")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogoDto.ProductoDto crearProducto(
            @Valid @RequestBody PeticionesCatalogo.Producto peticion) {
        return servicioProducto.crearDto(peticion);
    }

    @PutMapping("/productos/{id}")
    public CatalogoDto.ProductoDto actualizarProducto(
            @PathVariable long id, @Valid @RequestBody PeticionesCatalogo.Producto peticion) {
        return servicioProducto.actualizarDto(id, peticion);
    }

    @PostMapping("/productos/{id}/desactivacion")
    public ResultadoActivacionDto desactivarProducto(@PathVariable long id) {
        return servicioProducto.cambiarActivo(id, false);
    }

    @PostMapping("/productos/{id}/reactivacion")
    public ResultadoActivacionDto reactivarProducto(@PathVariable long id) {
        return servicioProducto.cambiarActivo(id, true);
    }

    // ---------------------------------------------------------------- variantes

    @PostMapping("/variantes")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogoDto.VarianteDto crearVariante(
            @Valid @RequestBody PeticionesCatalogo.Variante peticion) {
        return servicioVariante.crearDto(peticion);
    }

    @PutMapping("/variantes/{id}")
    public CatalogoDto.VarianteDto actualizarVariante(
            @PathVariable long id, @Valid @RequestBody PeticionesCatalogo.Variante peticion) {
        return servicioVariante.actualizarDto(id, peticion);
    }

    @PostMapping("/variantes/{id}/desactivacion")
    public ResultadoActivacionDto desactivarVariante(@PathVariable long id) {
        return servicioVariante.cambiarActivo(id, false);
    }

    @PostMapping("/variantes/{id}/reactivacion")
    public ResultadoActivacionDto reactivarVariante(@PathVariable long id) {
        return servicioVariante.cambiarActivo(id, true);
    }
}
