package com.alejandriamakeup.pos.ventas;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.caja.ServicioMovimientoCaja;
import com.alejandriamakeup.pos.caja.ServicioSesionCaja;
import com.alejandriamakeup.pos.caja.SesionCaja;
import com.alejandriamakeup.pos.catalogo.Descripcion;
import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.consecutivos.ServicioConsecutivo;
import com.alejandriamakeup.pos.consecutivos.TipoConsecutivo;
import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.ServicioCostoPromedio;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.dto.PeticionesVentas;
import com.alejandriamakeup.pos.ventas.dto.VentaDto;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * El cobro y su deshacer.
 *
 * <p>Todo lo que produce una venta —el consecutivo, la cabecera, las líneas
 * congeladas, la salida de inventario y, si fue en efectivo, la entrada al cajón—
 * ocurre dentro de <strong>una sola transacción</strong>. No hay compensaciones ni
 * pasos que se reintentan: o queda todo o no queda nada. Una venta cobrada cuyo
 * inventario no bajó, o un cajón que recibió plata de una venta que no existe, son
 * descuadres que no se notan el día que pasan.
 *
 * <p>Los invariantes que este servicio sostiene y que no se ven mirando la pantalla:
 *
 * <ul>
 *   <li>El precio, el costo y la descripción se congelan aquí. Cambiar un precio
 *       mañana no puede mover el margen de lo que se vendió hoy.
 *   <li>El total y el cambio los calcula el servidor. La petición no tiene campo para
 *       ninguno de los dos.
 *   <li>Solo el efectivo toca el cajón, y eso lo decide {@link ServicioMovimientoCaja},
 *       no este servicio: la regla vive en un solo sitio.
 *   <li>Vender sin stock se permite y se avisa. Vender sin costo, no.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ServicioVenta {

    private static final Logger log = LoggerFactory.getLogger(ServicioVenta.class);

    /**
     * El resultado de registrar: la venta y si de verdad se creó.
     *
     * <p>{@code creada} existe para que el endpoint pueda responder 201 al crear y 200
     * al repetir. La diferencia no es ceremonia REST: es lo único que le permite al
     * front notar que reutilizó un uuid, y vender dos veces seguidas el mismo producto
     * a dos clientas distintas es rutina en el mostrador. Con un solo código, la
     * segunda venta se tragaría en silencio y la tienda cobraría una.
     */
    public record Registro(VentaDto venta, boolean creada) {
    }

    private final VentaRepository ventaRepository;
    private final VentaItemRepository itemRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final ServicioSesionCaja servicioSesion;
    private final ServicioMovimientoCaja servicioMovimientoCaja;
    private final ServicioConsecutivo servicioConsecutivo;
    private final ServicioVariante servicioVariante;
    private final ServicioCostoPromedio servicioCostoPromedio;
    private final UsuarioRepository usuarioRepository;

    public ServicioVenta(VentaRepository ventaRepository,
                         VentaItemRepository itemRepository,
                         MovimientoInventarioRepository movimientoRepository,
                         ServicioSesionCaja servicioSesion,
                         ServicioMovimientoCaja servicioMovimientoCaja,
                         ServicioConsecutivo servicioConsecutivo,
                         ServicioVariante servicioVariante,
                         ServicioCostoPromedio servicioCostoPromedio,
                         UsuarioRepository usuarioRepository) {
        this.ventaRepository = ventaRepository;
        this.itemRepository = itemRepository;
        this.movimientoRepository = movimientoRepository;
        this.servicioSesion = servicioSesion;
        this.servicioMovimientoCaja = servicioMovimientoCaja;
        this.servicioConsecutivo = servicioConsecutivo;
        this.servicioVariante = servicioVariante;
        this.servicioCostoPromedio = servicioCostoPromedio;
        this.usuarioRepository = usuarioRepository;
    }

    // --------------------------------------------------------------- registrar

    /**
     * Registra la venta completa, o ninguna parte de ella.
     *
     * <p>Es idempotente por el {@code uuid} que genera el cliente: si ya existe una
     * venta con ese uuid se devuelve tal cual, sin crear nada. Lo que esto protege es
     * el reintento — la cajera pulsa dos veces, o la respuesta se pierde y el front
     * repite — y sin esta comprobación cada reintento cobraría de nuevo, bajaría el
     * inventario de nuevo y metería la plata en el cajón de nuevo.
     *
     * <p>El índice único {@code ux_venta_uuid} está debajo como red: si dos peticiones
     * llegaran a pasar la comprobación a la vez, la base rechaza la segunda en vez de
     * dejar dos ventas gemelas.
     */
    @Transactional
    public Registro registrar(PeticionesVentas.Venta peticion, long usuarioId) {
        Optional<Venta> yaRegistrada = ventaRepository.findByUuid(peticion.uuid());
        if (yaRegistrada.isPresent()) {
            Venta venta = yaRegistrada.get();
            log.info("Venta {} repetida con el mismo uuid: se devuelve la existente",
                    venta.getConsecutivo());
            return new Registro(aDto(venta), false);
        }

        // Por la guarda de la Fase 2 y no por buscarAbierta(): sin ella, la venta de
        // hoy entraría en la caja de ayer que quedó sin cerrar, y el descuadre no se
        // vería porque esa sesión cuadra consigo misma -- solo que abarca dos días.
        SesionCaja sesion = servicioSesion.sesionOperableHoy();
        Usuario usuario = usuario(usuarioId);

        List<PeticionesVentas.Venta.Linea> lineas = peticion.lineas();
        List<Variante> variantes = new ArrayList<>(lineas.size());
        long[] subtotales = new long[lineas.size()];
        long subtotal = 0;

        for (int i = 0; i < lineas.size(); i++) {
            Variante variante = servicioVariante.buscar(lineas.get(i).varianteId());
            exigirCosto(variante);
            variantes.add(variante);
            subtotales[i] = (long) lineas.get(i).cantidad() * variante.getPrecioVenta();
            subtotal += subtotales[i];
        }

        long descuento = peticion.descuento() == null ? 0 : peticion.descuento();
        if (descuento > subtotal) {
            throw ErrorDeAplicacion.peticionInvalida("El descuento de " + descuento
                    + " es mayor que el subtotal de " + subtotal + ".");
        }
        long total = subtotal - descuento;

        Venta venta = new Venta();
        venta.setUuid(peticion.uuid());
        venta.setConsecutivo(servicioConsecutivo.siguiente(TipoConsecutivo.VENTA));
        venta.setSesionCaja(sesion);
        venta.setUsuario(usuario);
        venta.setFecha(Fechas.ahora());
        venta.setSubtotal(subtotal);
        venta.setDescuento(descuento);
        venta.setTotal(total);
        venta.setMetodoPago(peticion.metodoPago());
        venta.setEstado(EstadoVenta.COMPLETADA);
        aplicarEfectivo(venta, peticion.efectivoRecibido(), total);
        ventaRepository.save(venta);

        long[] reparto = Prorrateo.repartir(subtotales, descuento);

        for (int i = 0; i < lineas.size(); i++) {
            Variante variante = variantes.get(i);
            int cantidad = lineas.get(i).cantidad();

            itemRepository.save(VentaItem.builder()
                    .venta(venta)
                    .variante(variante)
                    .cantidad(cantidad)
                    .precioUnitarioCongelado(variante.getPrecioVenta())
                    .costoUnitarioCongelado(variante.getCostoPromedio())
                    .descripcionCongelada(describir(variante))
                    .descuentoProrrateado(reparto[i])
                    .subtotal(subtotales[i])
                    .build());

            movimientoRepository.save(MovimientoInventario.builder()
                    .variante(variante)
                    .tipo(TipoMovimientoInventario.VENTA)
                    .cantidad(-cantidad)
                    .costoUnitario(variante.getCostoPromedio())
                    .venta(venta)
                    .usuario(usuario)
                    .fecha(venta.getFecha())
                    .motivo("Venta " + venta.getConsecutivo())
                    .build());
        }

        // El costo promedio NO se recalcula al vender, y no es un olvido: en el replay
        // una salida consume unidades al promedio vigente y no lo mueve, así que
        // llamar a recalcular() aquí sería rehacer el ledger entero de cada variante,
        // en el camino del cobro, para volver a guardar el mismo número.
        servicioMovimientoCaja.registrarVenta(venta);

        log.info("Venta {} por {} ({}): {} línea(s) en la sesión {}", venta.getConsecutivo(),
                total, venta.getMetodoPago(), lineas.size(), sesion.getConsecutivo());
        return new Registro(aDto(venta), true);
    }

    // ------------------------------------------------------------------ anular

    /**
     * Anula una venta completada: devuelve el inventario y, si fue en efectivo,
     * devuelve la plata.
     *
     * <p>{@code COMPLETADA -> ANULADA} es terminal. Exigir el estado de partida es lo
     * que impide la doble anulación, que devolvería el inventario dos veces y sacaría
     * del cajón el doble de lo que entró.
     *
     * <p>El movimiento de caja va contra la <strong>sesión de hoy</strong>, nunca
     * contra la de la venta: ver {@code ServicioMovimientoCaja.registrarAnulacionDeVenta}.
     *
     * <p>Aquí sí se recalcula el costo promedio: la anulación es una entrada al
     * ledger, y una entrada sí mueve el promedio.
     */
    @Transactional
    public VentaDto anular(long ventaId, String motivo, long usuarioId) {
        Venta venta = buscar(ventaId);

        if (venta.getEstado() != EstadoVenta.COMPLETADA) {
            throw ErrorDeAplicacion.conflicto("ESTADO_DE_VENTA_INVALIDO",
                    "La venta " + venta.getConsecutivo() + " está en estado " + venta.getEstado()
                            + ". Una venta anulada ya devolvió su inventario y su plata.");
        }

        List<VentaItem> items = itemRepository.findByVentaId(venta.getId());
        Usuario usuario = usuario(usuarioId);
        LocalDateTime cuando = Fechas.ahora();
        Set<Long> variantes = new LinkedHashSet<>();

        for (VentaItem item : items) {
            movimientoRepository.save(MovimientoInventario.builder()
                    .variante(item.getVariante())
                    .tipo(TipoMovimientoInventario.ANULACION)
                    .cantidad(item.getCantidad())
                    .costoUnitario(item.getCostoUnitarioCongelado())
                    .venta(venta)
                    .usuario(usuario)
                    .fecha(cuando)
                    .motivo("Anulación de la venta " + venta.getConsecutivo() + ": " + motivo)
                    .build());
            variantes.add(item.getVariante().getId());
        }

        venta.setEstado(EstadoVenta.ANULADA);
        venta.setFechaAnulacion(cuando);
        venta.setMotivoAnulacion(motivo.strip());
        venta.setUsuarioAnulacion(usuario);
        ventaRepository.save(venta);

        servicioMovimientoCaja.registrarAnulacionDeVenta(venta, usuarioId);
        variantes.forEach(servicioCostoPromedio::recalcular);

        log.info("Venta {} anulada: {}", venta.getConsecutivo(), motivo);
        return aDto(venta, items);
    }

    // --------------------------------------------------------------- consultar

    public VentaDto porId(long ventaId) {
        return aDto(buscar(ventaId));
    }

    /**
     * Las ventas de un día, en orden de la mañana a la noche.
     *
     * <p>El rango se cierra en {@code 23:59:59} y no en el día siguiente porque el
     * esquema guarda las fechas como TEXT de ancho fijo con precisión de segundos
     * —{@link Fechas} trunca ahí—, así que no existe ninguna venta entre ese instante
     * y la medianoche. Comparar ese texto es comparar cronológicamente.
     *
     * <p>Sin paginar: son las ventas de un día en una tienda, y la convención del
     * proyecto es cargar el listado completo.
     */
    public List<VentaDto.Resumen> listar(LocalDate fecha) {
        LocalDate dia = fecha == null ? LocalDate.now() : fecha;

        return ventaRepository
                .findByFechaBetweenOrderByFechaAsc(dia.atStartOfDay(), dia.atTime(23, 59, 59))
                .stream()
                .map(venta -> new VentaDto.Resumen(
                        venta.getId(),
                        venta.getConsecutivo(),
                        String.valueOf(venta.getFecha()),
                        venta.getTotal(),
                        venta.getMetodoPago(),
                        venta.getEstado(),
                        venta.getUsuario().getNombre(),
                        venta.getMotivoAnulacion(),
                        venta.getRutaRecibo()))
                .toList();
    }

    // ------------------------------------------------------------------- apoyo

    /**
     * Una variante sin costo promedio no se puede vender.
     *
     * <p>El cero no es un costo: significa que por esa variante nunca entró mercancía
     * valorada, o que todas las compras que la valoraban se anularon y el replay dejó
     * el promedio otra vez en cero. En los dos casos, cobrarla congelaría costo 0 en
     * {@code venta_item} y la métrica de margen diría para siempre que ese producto se
     * vendió con 100% de utilidad.
     *
     * <p>El mensaje dice qué hacer. Un rechazo a secas, con una clienta enfrente, deja
     * a quien cobra sin salida y empuja al atajo.
     */
    private void exigirCosto(Variante variante) {
        if (variante.getCostoPromedio() == 0) {
            throw ErrorDeAplicacion.conflicto("VARIANTE_SIN_COSTO",
                    "«" + describir(variante) + "» no tiene costo registrado, así que todavía no "
                            + "se puede vender. Hay que recibir la compra pendiente de ese producto "
                            + "—o cargarlo en las existencias iniciales— y volver a cobrar.");
        }
    }

    /**
     * Con EFECTIVO se exige lo recibido y el cambio lo calcula el servidor. Con
     * cualquier otro método los dos campos quedan nulos aunque el cliente los mande:
     * un pago por datáfono no tiene vueltas que dar.
     */
    private void aplicarEfectivo(Venta venta, Long recibido, long total) {
        if (venta.getMetodoPago() != MetodoPago.EFECTIVO) {
            return;
        }
        if (recibido == null) {
            throw ErrorDeAplicacion.peticionInvalida(
                    "Con pago en efectivo hay que decir cuánto se recibió.");
        }
        if (recibido < total) {
            throw ErrorDeAplicacion.peticionInvalida("El efectivo recibido (" + recibido
                    + ") no alcanza para el total de " + total + ".");
        }
        venta.setEfectivoRecibido(recibido);
        venta.setCambio(recibido - total);
    }

    /**
     * Marca, producto, tono y tamaño, lo que exista. Es lo que se congela y se imprime.
     *
     * <p><strong>Conviven dos formatos en la base y así se queda.</strong> Hasta la
     * Fase 9 las partes se unían con espacios —{@code "Maybelline Labial mate Rojo
     * 5 ml"}—; desde la <strong>Fase 10</strong> se unen con {@link Descripcion#SEPARADOR}
     * y se sanean: {@code "Maybelline|Labial mate|Rojo|5 ml"}. Las ventas anteriores
     * conservan la forma vieja porque {@code venta_item} es inmutable, y esa es la
     * decisión correcta: reescribir una descripción congelada sería falsificar el
     * comprobante de lo que se entregó. Quien vea las dos formas en un listado dentro
     * de seis meses, o regenere el recibo de una venta vieja y lo vea con espacios, no
     * está mirando un error: está mirando la fecha de corte.
     */
    private String describir(Variante variante) {
        String descripcion = Descripcion.de(
                variante.getProducto().getMarca().getNombre(),
                variante.getProducto().getNombre(),
                variante.getTono(),
                variante.getTamano());
        return descripcion.isBlank() ? "Variante " + variante.getId() : descripcion;
    }

    private Venta buscar(long ventaId) {
        return ventaRepository.findById(ventaId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe la venta " + ventaId));
    }

    private Usuario usuario(long usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));
    }

    private VentaDto aDto(Venta venta) {
        return aDto(venta, itemRepository.findByVentaId(venta.getId()));
    }

    /**
     * El mapeo vive aquí y no en el controlador porque lee asociaciones LAZY
     * ({@code usuario.nombre}, {@code variante.producto}) y con
     * {@code open-in-view: false} fuera de la transacción eso revienta.
     */
    private VentaDto aDto(Venta venta, List<VentaItem> items) {
        List<VentaDto.LineaDto> lineas = items.stream()
                .map(item -> new VentaDto.LineaDto(
                        item.getVariante().getId(),
                        item.getDescripcionCongelada(),
                        item.getCantidad(),
                        item.getPrecioUnitarioCongelado(),
                        item.getDescuentoProrrateado(),
                        item.getSubtotal()))
                .toList();

        return new VentaDto(
                venta.getId(),
                venta.getUuid(),
                venta.getConsecutivo(),
                String.valueOf(venta.getFecha()),
                venta.getSesionCaja().getId(),
                venta.getUsuario().getNombre(),
                venta.getSubtotal(),
                venta.getDescuento(),
                venta.getTotal(),
                venta.getMetodoPago(),
                venta.getEfectivoRecibido(),
                venta.getCambio(),
                venta.getEstado(),
                venta.getFechaAnulacion() == null ? null : String.valueOf(venta.getFechaAnulacion()),
                venta.getMotivoAnulacion(),
                venta.getRutaRecibo(),
                lineas,
                negativos(items));
    }

    /**
     * Las variantes de esta venta que quedaron bajo cero, con el stock del instante en
     * que se pregunta. Una consulta por variante distinta: son las líneas de un
     * carrito, no un catálogo.
     */
    private List<VentaDto.VarianteEnNegativoDto> negativos(List<VentaItem> items) {
        List<VentaDto.VarianteEnNegativoDto> negativos = new ArrayList<>();
        Set<Long> vistas = new LinkedHashSet<>();

        for (VentaItem item : items) {
            if (!vistas.add(item.getVariante().getId())) {
                continue;
            }
            long stock = movimientoRepository.stockDe(item.getVariante().getId());
            if (stock < 0) {
                negativos.add(new VentaDto.VarianteEnNegativoDto(
                        item.getVariante().getId(), item.getDescripcionCongelada(), stock));
            }
        }
        return negativos;
    }
}
