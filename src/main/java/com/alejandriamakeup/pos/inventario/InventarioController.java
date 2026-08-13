package com.alejandriamakeup.pos.inventario;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.inventario.dto.MovimientoInventarioDto;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.seguridad.SesionHttp;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * Carga inicial y ajustes de inventario. Las dos, solo para la DUENA.
 *
 * <p>Aquí no hay endpoints de lectura a propósito: el stock se lee del catálogo
 * completo, que ya lo trae calculado y no expone costos. Un
 * {@code GET /api/v1/inventario/movimientos} sería el sitio natural por donde se
 * filtrarían los costos unitarios a una sesión de EMPLEADA, así que no existe hasta
 * que haga falta y se piense con cuidado.
 */
@RestController
@RequestMapping("/api/v1/inventario")
public class InventarioController {

    private final ServicioInventario servicioInventario;

    public InventarioController(ServicioInventario servicioInventario) {
        this.servicioInventario = servicioInventario;
    }

    @PostMapping("/carga-inicial")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cargaInicial(
            @Valid @RequestBody PeticionesInventario.CargaInicial peticion, HttpSession sesion) {

        List<MovimientoInventarioDto> registrados =
                servicioInventario.cargaInicial(peticion, SesionHttp.usuarioIdObligatorio(sesion));

        return Map.of("variantesCargadas", registrados.size(), "movimientos", registrados);
    }

    @PostMapping("/ajustes")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoInventarioDto ajustar(@Valid @RequestBody PeticionesInventario.Ajuste peticion,
                                           HttpSession sesion) {
        return servicioInventario.ajustar(peticion, SesionHttp.usuarioIdObligatorio(sesion));
    }
}
