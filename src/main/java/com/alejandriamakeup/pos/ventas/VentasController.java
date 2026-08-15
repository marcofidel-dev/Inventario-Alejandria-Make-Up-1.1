package com.alejandriamakeup.pos.ventas;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.seguridad.SesionHttp;
import com.alejandriamakeup.pos.ventas.dto.PeticionesVentas;
import com.alejandriamakeup.pos.ventas.dto.VentaDto;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * La API del punto de venta.
 *
 * <p>Sin listado todavía: llega en la Fase 9, que es donde hace falta para reimprimir
 * un recibo y para encontrar la venta que se va a anular. Hoy el endpoint de
 * anulación solo se alcanza con el id en la mano.
 *
 * <p>Ninguna respuesta de aquí lleva costos ni márgenes, para ningún rol. Ver
 * {@link VentaDto}.
 */
@RestController
@RequestMapping("/api/v1/ventas")
public class VentasController {

    private final ServicioVenta servicioVenta;

    public VentasController(ServicioVenta servicioVenta) {
        this.servicioVenta = servicioVenta;
    }

    /**
     * Cobra. Responde <strong>201 si creó la venta y 200 si devolvió una existente</strong>
     * con el mismo {@code uuid}.
     *
     * <p><strong>Contrato del uuid:</strong> lo genera el cliente al abrir el carrito y
     * lo descarta en cuanto un cobro sale bien. Nunca se reutiliza. El uuid identifica
     * <em>este</em> carrito, no "una venta cualquiera de esta caja".
     *
     * <p>Los dos códigos existen por eso. Vender dos veces seguidas el mismo producto a
     * dos clientas distintas es rutina en el mostrador: si el front reutilizara el uuid,
     * el servidor devolvería la venta anterior y el segundo cobro se tragaría en
     * silencio —la tienda entregaría dos productos y cobraría uno—. Con 201/200 el front
     * puede notar la reutilización y avisar, en vez de que el error salga a la luz
     * cuadrando la caja.
     *
     * <p>El 200 es idempotencia legítima: la respuesta perdida, el doble clic, el
     * reintento. Repetir la petición no cobra de nuevo, no baja el inventario de nuevo
     * y no vuelve a meter plata en el cajón.
     */
    @PostMapping
    public ResponseEntity<VentaDto> registrar(@Valid @RequestBody PeticionesVentas.Venta peticion,
                                              HttpSession sesion) {
        ServicioVenta.Registro registro =
                servicioVenta.registrar(peticion, SesionHttp.usuarioIdObligatorio(sesion));

        return ResponseEntity
                .status(registro.creada() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(registro.venta());
    }

    @GetMapping("/{id}")
    public VentaDto porId(@PathVariable long id) {
        return servicioVenta.porId(id);
    }

    /**
     * Anula. Solo la DUENA, por {@code Permiso.ANULAR_VENTAS}: deshacer y hacer no son
     * la misma capacidad.
     */
    @PostMapping("/{id}/anulacion")
    public VentaDto anular(@PathVariable long id,
                           @Valid @RequestBody PeticionesVentas.Anulacion peticion,
                           HttpSession sesion) {
        return servicioVenta.anular(id, peticion.motivo(), SesionHttp.usuarioIdObligatorio(sesion));
    }
}
