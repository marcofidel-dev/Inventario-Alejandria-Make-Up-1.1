package com.alejandriamakeup.pos.ventas;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.seguridad.SesionHttp;
import com.alejandriamakeup.pos.ventas.recibo.ServicioRecibo;
import com.alejandriamakeup.pos.ventas.dto.PeticionesVentas;
import com.alejandriamakeup.pos.ventas.dto.VentaDto;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * La API del punto de venta.
 *
 * <p>Ninguna respuesta de aquí lleva costos ni márgenes, para ningún rol. Ver
 * {@link VentaDto}.
 */
@RestController
@RequestMapping("/api/v1/ventas")
public class VentasController {

    private final ServicioVenta servicioVenta;
    private final ServicioRecibo servicioRecibo;

    public VentasController(ServicioVenta servicioVenta, ServicioRecibo servicioRecibo) {
        this.servicioVenta = servicioVenta;
        this.servicioRecibo = servicioRecibo;
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
     *
     * <p><strong>El recibo se genera aquí, después de que la transacción de la venta
     * cerró</strong> — que es lo que significa "después del commit" en la práctica: el
     * servicio ya retornó. {@code generarSiFalta} nunca lanza, así que un PDF que falle
     * deja la venta cobrada y sin comprobante, no un error en pantalla. La ruta que
     * devuelve se pega al DTO que ya está armado en vez de releer la venta: por un solo
     * campo no se hace otra consulta en el camino del cobro.
     */
    @PostMapping
    public ResponseEntity<VentaDto> registrar(@Valid @RequestBody PeticionesVentas.Venta peticion,
                                              HttpSession sesion) {
        ServicioVenta.Registro registro =
                servicioVenta.registrar(peticion, SesionHttp.usuarioIdObligatorio(sesion));

        VentaDto venta = registro.venta();
        if (registro.creada()) {
            venta = venta.conRutaRecibo(servicioRecibo.generarSiFalta(venta.id()));
        }

        return ResponseEntity
                .status(registro.creada() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(venta);
    }

    /**
     * Las ventas de un día. Sin {@code fecha}, las de hoy — que es lo que se pide el
     * 99% de las veces y ahorra que la pantalla tenga que calcular la fecha local.
     */
    @GetMapping
    public List<VentaDto.Resumen> listar(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return servicioVenta.listar(fecha);
    }

    /**
     * Las ventas que quedaron sin comprobante, para recuperarlas en lote.
     *
     * <p>Va antes de {@code /{id}} en el archivo por legibilidad; quien resuelve es
     * Spring, que prefiere el patrón literal sobre el de variable.
     */
    @GetMapping("/sin-recibo")
    public List<VentaDto.Resumen> sinRecibo() {
        return servicioRecibo.sinRecibo();
    }

    @GetMapping("/{id}")
    public VentaDto porId(@PathVariable long id) {
        return servicioVenta.porId(id);
    }

    /**
     * El PDF del recibo, para abrirlo en el visor del sistema.
     *
     * <p>{@code inline} y no {@code attachment}: lo que se quiere es verlo e imprimirlo
     * en el momento, no acumular archivos en la carpeta de descargas.
     *
     * <p>Con {@code REGISTRAR_VENTAS}, o sea los dos roles: la EMPLEADA tiene que poder
     * reimprimir el comprobante de su propia venta.
     *
     * <p><strong>De una venta anulada se entrega igual.</strong> El PDF no se borra ni
     * se altera nunca: es el registro de lo que se le entregó físicamente a la clienta.
     * El estado lo muestra el listado, que es donde significa algo.
     */
    @GetMapping("/{id}/recibo")
    public ResponseEntity<byte[]> recibo(@PathVariable long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + servicioRecibo.nombreDeArchivo(id) + "\"")
                .body(servicioRecibo.pdf(id));
    }

    /**
     * Regenera el recibo de una venta que se quedó sin él.
     *
     * <p>Si ya lo tiene, devuelve el que hay sin volver a escribir nada: regenerar no
     * puede ser una forma de cambiar un comprobante ya entregado.
     */
    @PostMapping("/{id}/recibo")
    public VentaDto regenerarRecibo(@PathVariable long id) {
        servicioRecibo.generarSiFalta(id);
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
