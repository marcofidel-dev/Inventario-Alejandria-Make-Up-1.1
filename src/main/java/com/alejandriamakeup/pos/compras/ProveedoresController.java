package com.alejandriamakeup.pos.compras;

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

import com.alejandriamakeup.pos.catalogo.dto.ResultadoActivacionDto;
import com.alejandriamakeup.pos.compras.dto.PeticionesCompras;
import com.alejandriamakeup.pos.compras.dto.ProveedorDto;

import jakarta.validation.Valid;

/**
 * La API de proveedores.
 *
 * <p>Sin verbo DELETE, igual que el catálogo: un proveedor no se borra, se
 * desactiva. Borrarlo se llevaría el historial de a quién se le compró qué, que es
 * justo lo que alguien va a querer mirar cuando un lote salga malo.
 *
 * <p>Quién puede llamar a qué vive en {@code ReglasDeAcceso}.
 */
@RestController
@RequestMapping("/api/v1/proveedores")
public class ProveedoresController {

    private final ServicioProveedor servicioProveedor;

    public ProveedoresController(ServicioProveedor servicioProveedor) {
        this.servicioProveedor = servicioProveedor;
    }

    @GetMapping
    public List<ProveedorDto> listar() {
        return servicioProveedor.todos().stream().map(ProveedorDto::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProveedorDto crear(@Valid @RequestBody PeticionesCompras.Proveedor peticion) {
        return ProveedorDto.de(servicioProveedor.crear(peticion));
    }

    @PutMapping("/{id}")
    public ProveedorDto actualizar(@PathVariable long id,
                                   @Valid @RequestBody PeticionesCompras.Proveedor peticion) {
        return ProveedorDto.de(servicioProveedor.actualizar(id, peticion));
    }

    @PostMapping("/{id}/desactivacion")
    public ResultadoActivacionDto desactivar(@PathVariable long id) {
        return servicioProveedor.cambiarActivo(id, false);
    }

    @PostMapping("/{id}/reactivacion")
    public ResultadoActivacionDto reactivar(@PathVariable long id) {
        return servicioProveedor.cambiarActivo(id, true);
    }
}
