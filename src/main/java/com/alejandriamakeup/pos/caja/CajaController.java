package com.alejandriamakeup.pos.caja;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.caja.dto.AnotarPeticion;
import com.alejandriamakeup.pos.caja.dto.ArqueoDto;
import com.alejandriamakeup.pos.caja.dto.CerrarSesionPeticion;
import com.alejandriamakeup.pos.caja.dto.MovimientoCajaDto;
import com.alejandriamakeup.pos.caja.dto.NotaSesionCajaDto;
import com.alejandriamakeup.pos.caja.dto.RegistrarMovimientoPeticion;
import com.alejandriamakeup.pos.caja.dto.SesionDto;
import com.alejandriamakeup.pos.seguridad.SesionHttp;
import com.alejandriamakeup.pos.usuarios.Rol;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * La API de caja.
 *
 * <p>Las reglas de quién puede llamar a qué no están aquí: viven en
 * {@code ReglasDeAcceso} y las aplica {@code InterceptorAutorizacion} antes de que
 * este controlador se ejecute. Lo que sí queda aquí es de quién es la sesión, que
 * depende de la fila y no de la ruta.
 */
@RestController
@RequestMapping("/api/v1/caja")
public class CajaController {

    private final ServicioSesionCaja servicioSesion;
    private final ServicioMovimientoCaja servicioMovimiento;

    public CajaController(ServicioSesionCaja servicioSesion, ServicioMovimientoCaja servicioMovimiento) {
        this.servicioSesion = servicioSesion;
        this.servicioMovimiento = servicioMovimiento;
    }

    @PostMapping("/sesiones")
    @ResponseStatus(HttpStatus.CREATED)
    public SesionDto abrir(HttpSession sesion) {
        // Sin cuerpo: no hay nada que declarar al abrir. Lo que se mande se ignora.
        return servicioSesion.abrir(SesionHttp.usuarioIdObligatorio(sesion));
    }

    @GetMapping("/sesiones/actual")
    public SesionDto actual() {
        return servicioSesion.actual();
    }

    @GetMapping("/sesiones")
    public List<SesionDto> listar(HttpSession sesion) {
        return servicioSesion.listar(SesionHttp.usuarioIdObligatorio(sesion), rol(sesion));
    }

    @GetMapping("/sesiones/{id}")
    public SesionDto porId(@PathVariable long id, HttpSession sesion) {
        return servicioSesion.porId(id, SesionHttp.usuarioIdObligatorio(sesion), rol(sesion));
    }

    @GetMapping("/sesiones/{id}/movimientos")
    public List<MovimientoCajaDto> movimientos(@PathVariable long id, HttpSession sesion) {
        return servicioSesion.movimientosDe(id, SesionHttp.usuarioIdObligatorio(sesion), rol(sesion));
    }

    /** La única respuesta del sistema que revela esperado, contado y diferencia. */
    @PostMapping("/sesiones/{id}/cierre")
    public ArqueoDto cerrar(@PathVariable long id,
                            @Valid @RequestBody CerrarSesionPeticion peticion,
                            HttpSession sesion) {
        return servicioSesion.cerrar(id, peticion, SesionHttp.usuarioIdObligatorio(sesion));
    }

    @GetMapping("/sesiones/{id}/notas")
    public List<NotaSesionCajaDto> notas(@PathVariable long id, HttpSession sesion) {
        return servicioSesion.notasDe(id, SesionHttp.usuarioIdObligatorio(sesion), rol(sesion));
    }

    /**
     * Agrega una nota. No modifica la sesión: las explicaciones se acumulan, igual
     * que los movimientos, y por eso esto es un POST y no un PUT.
     */
    @PostMapping("/sesiones/{id}/notas")
    @ResponseStatus(HttpStatus.CREATED)
    public NotaSesionCajaDto anotar(@PathVariable long id,
                                    @Valid @RequestBody AnotarPeticion peticion,
                                    HttpSession sesion) {
        return servicioSesion.anotar(id, peticion.texto(),
                SesionHttp.usuarioIdObligatorio(sesion), rol(sesion));
    }

    @PostMapping("/movimientos")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoCajaDto registrarMovimiento(@Valid @RequestBody RegistrarMovimientoPeticion peticion,
                                                 HttpSession sesion) {
        return MovimientoCajaDto.de(servicioMovimiento.registrarManual(
                peticion.tipo(), peticion.monto(), peticion.concepto(),
                SesionHttp.usuarioIdObligatorio(sesion)));
    }

    private Rol rol(HttpSession sesion) {
        return SesionHttp.rolObligatorio(sesion);
    }
}
