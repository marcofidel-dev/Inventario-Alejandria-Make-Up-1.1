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

import com.alejandriamakeup.pos.compras.dto.CompraDto;
import com.alejandriamakeup.pos.compras.dto.PeticionesCompras;
import com.alejandriamakeup.pos.compras.dto.PreviaAnulacionDto;
import com.alejandriamakeup.pos.compras.dto.PreviaRecepcionDto;
import com.alejandriamakeup.pos.seguridad.SesionHttp;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * La API de compras.
 *
 * <p>Sin verbo DELETE. Un borrador que no iba se <strong>descarta</strong> y una
 * compra recibida se <strong>anula</strong>: son dos rutas distintas con dos
 * permisos distintos porque tienen consecuencias distintas — descartar no revierte
 * nada, anular devuelve stock y puede dejar variantes en negativo.
 *
 * <p>Ninguna de las dos previas escribe. Están para que quien va a confirmar vea
 * antes qué va a pasar; los números que devuelven no vuelven al servidor, que
 * recalcula todo con el ledger del momento de confirmar.
 */
@RestController
@RequestMapping("/api/v1/compras")
public class ComprasController {

    private final ServicioCompra servicioCompra;
    private final ServicioRecepcion servicioRecepcion;

    public ComprasController(ServicioCompra servicioCompra, ServicioRecepcion servicioRecepcion) {
        this.servicioCompra = servicioCompra;
        this.servicioRecepcion = servicioRecepcion;
    }

    @GetMapping
    public List<CompraDto> listar() {
        return servicioCompra.listar();
    }

    @GetMapping("/{id}")
    public CompraDto porId(@PathVariable long id) {
        return servicioCompra.porId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompraDto crearBorrador(@Valid @RequestBody PeticionesCompras.Compra peticion,
                                   HttpSession sesion) {
        return servicioCompra.crearBorrador(peticion, SesionHttp.usuarioIdObligatorio(sesion));
    }

    @PutMapping("/{id}")
    public CompraDto actualizarBorrador(@PathVariable long id,
                                        @Valid @RequestBody PeticionesCompras.Compra peticion) {
        return servicioCompra.actualizarBorrador(id, peticion);
    }

    @PostMapping("/{id}/descarte")
    public CompraDto descartar(@PathVariable long id,
                               @Valid @RequestBody PeticionesCompras.Baja peticion,
                               HttpSession sesion) {
        return servicioCompra.descartar(id, peticion.motivo(),
                SesionHttp.usuarioIdObligatorio(sesion));
    }

    @GetMapping("/{id}/previa-recepcion")
    public PreviaRecepcionDto previaDeRecepcion(@PathVariable long id) {
        return servicioRecepcion.previaDeRecepcion(id);
    }

    @PostMapping("/{id}/recepcion")
    public CompraDto recibir(@PathVariable long id, HttpSession sesion) {
        return servicioRecepcion.recibir(id, SesionHttp.usuarioIdObligatorio(sesion));
    }

    @GetMapping("/{id}/previa-anulacion")
    public PreviaAnulacionDto previaDeAnulacion(@PathVariable long id) {
        return servicioRecepcion.previaDeAnulacion(id);
    }

    @PostMapping("/{id}/anulacion")
    public CompraDto anular(@PathVariable long id,
                            @Valid @RequestBody PeticionesCompras.Baja peticion,
                            HttpSession sesion) {
        return servicioRecepcion.anular(id, peticion.motivo(),
                SesionHttp.usuarioIdObligatorio(sesion));
    }
}
