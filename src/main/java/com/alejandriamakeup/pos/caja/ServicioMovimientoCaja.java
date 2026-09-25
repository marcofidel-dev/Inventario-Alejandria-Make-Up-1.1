package com.alejandriamakeup.pos.caja;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Movimientos del cajón. Append-only: aquí solo se agrega.
 *
 * <p>El efectivo esperado <strong>nunca</strong> se guarda acumulado en ninguna
 * parte: se calcula con {@code SUM(monto)} cada vez que hace falta,
 * igual que el stock es la suma del ledger de inventario.
 */
@Service
@Transactional(readOnly = true)
public class ServicioMovimientoCaja {

    private static final Logger log = LoggerFactory.getLogger(ServicioMovimientoCaja.class);

    private final MovimientoCajaRepository movimientoRepository;
    private final ServicioSesionCaja servicioSesion;
    private final UsuarioRepository usuarioRepository;

    public ServicioMovimientoCaja(MovimientoCajaRepository movimientoRepository,
                                  ServicioSesionCaja servicioSesion,
                                  UsuarioRepository usuarioRepository) {
        this.movimientoRepository = movimientoRepository;
        this.servicioSesion = servicioSesion;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Registra un retiro, un ingreso o un gasto sobre la sesión abierta.
     *
     * @param montoPositivo siempre positivo; el signo lo pone el tipo
     */
    @Transactional
    public MovimientoCaja registrarManual(TipoMovimientoCaja tipo, long montoPositivo,
                                          String concepto, long usuarioId) {
        if (!tipo.esManual()) {
            throw ErrorDeAplicacion.peticionInvalida("El movimiento de tipo " + tipo
                    + " no se registra a mano: nace de una venta.");
        }
        if (montoPositivo <= 0) {
            throw ErrorDeAplicacion.peticionInvalida("El monto debe ser positivo.");
        }

        // Por la guarda y no por buscarAbierta(): un gasto de hoy no puede caer en la
        // caja de ayer que quedó sin cerrar.
        SesionCaja sesion = servicioSesion.sesionOperableHoy();

        MovimientoCaja movimiento = guardar(sesion, tipo, tipo.conSigno(montoPositivo),
                concepto, usuarioId, null);
        log.info("Movimiento de caja {} por {} en la sesión {}", tipo, movimiento.getMonto(),
                sesion.getConsecutivo());
        return movimiento;
    }

    /**
     * El puente con las ventas: <strong>solo el efectivo toca el cajón</strong>.
     *
     * <p>Una venta por tarjeta, Nequi, Daviplata o transferencia no genera
     * movimiento aquí — se concilia aparte contra el extracto. Si lo generara, el
     * arqueo del día pediría plata física que nunca entró al cajón y toda sesión
     * cerraría con faltante.
     *
     * @return el movimiento creado, o {@code null} si el método de pago no es efectivo
     */
    @Transactional
    public MovimientoCaja registrarVenta(Venta venta) {
        if (noMueveElCajon(venta)) {
            return null;
        }

        return guardar(venta.getSesionCaja(), TipoMovimientoCaja.VENTA_EFECTIVO,
                TipoMovimientoCaja.VENTA_EFECTIVO.conSigno(venta.getTotal()),
                "Venta " + venta.getConsecutivo(), venta.getUsuario().getId(), venta);
    }

    /**
     * Devuelve al cajón lo que cobró una venta anulada.
     *
     * <p><strong>Golpea la sesión operable de hoy, nunca la de la venta.</strong> Una
     * sesión cerrada es inmutable: su efectivo esperado y su diferencia quedaron
     * congelados y firmados en el arqueo, y meterle un movimiento después haría que
     * los números de un cierre ya revisado dejaran de cuadrar con su propia lista de
     * movimientos. La plata sale del cajón que está abierto ahora, que es el cajón del
     * que de verdad sale.
     *
     * <p>No hay una rama para "la sesión original sigue abierta": cuando lo está,
     * {@code sesionOperableHoy()} devuelve esa misma sesión. Un camino solo, que es lo
     * que impide que el caso raro sea el único que nadie ejercita.
     *
     * <p>Sin caja abierta, 409. No se devuelve efectivo de un cajón cerrado: si la
     * anulación fuera igual, quedaría una venta anulada cuya plata no salió de
     * ninguna parte.
     *
     * @return el movimiento creado, o {@code null} si la venta no fue en efectivo
     */
    @Transactional
    public MovimientoCaja registrarAnulacionDeVenta(Venta venta, long usuarioId) {
        if (noMueveElCajon(venta)) {
            return null;
        }

        SesionCaja sesion = servicioSesion.sesionOperableHoy();
        return guardar(sesion, TipoMovimientoCaja.ANULACION,
                TipoMovimientoCaja.ANULACION.conSigno(venta.getTotal()),
                "Anulación de la venta " + venta.getConsecutivo(), usuarioId, venta);
    }

    public List<MovimientoCaja> deSesion(long sesionId) {
        return movimientoRepository.findBySesionIdOrderByFechaAsc(sesionId);
    }

    /** La suma con signo de los movimientos: es el efectivo esperado, sin más sumandos. */
    public long sumaDe(long sesionId) {
        return movimientoRepository.sumaDe(sesionId);
    }

    /**
     * Si esta venta no tiene por qué tocar el cajón, ni al cobrarse ni al anularse.
     *
     * <p>Dos casos, y el segundo no es teórico. El primero es el método de pago: solo
     * el efectivo entra al cajón.
     *
     * <p>El segundo es el <strong>total en cero</strong> — un obsequio, o un descuento
     * del 100%, que el sistema permite porque {@code descuento <= subtotal} admite la
     * igualdad. Ahí no entra ni sale un peso, y escribir el movimiento de todos modos
     * violaría el {@code CHECK (monto <> 0)} de {@code movimiento_caja} en el flush, ya
     * con la venta y su inventario escritos. La transacción lo revertiría todo, así que
     * no quedarían datos a medias, pero la clienta se iría sin su obsequio y con un 500
     * en pantalla que no explica nada.
     */
    private boolean noMueveElCajon(Venta venta) {
        if (venta.getMetodoPago() != MetodoPago.EFECTIVO) {
            log.debug("Venta {} por {}: no toca movimiento_caja", venta.getConsecutivo(),
                    venta.getMetodoPago());
            return true;
        }
        if (venta.getTotal() == 0) {
            log.debug("Venta {} con total 0: no toca movimiento_caja", venta.getConsecutivo());
            return true;
        }
        return false;
    }

    private MovimientoCaja guardar(SesionCaja sesion, TipoMovimientoCaja tipo, long montoConSigno,
                                   String concepto, long usuarioId, Venta venta) {
        if (sesion.getEstado() != EstadoSesionCaja.ABIERTA) {
            throw ErrorDeAplicacion.conflicto("SESION_CERRADA",
                    "La sesión " + sesion.getConsecutivo() + " está cerrada y es inmutable: "
                            + "no admite movimientos nuevos.");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));

        return movimientoRepository.save(MovimientoCaja.builder()
                .sesion(sesion)
                .tipo(tipo)
                .monto(montoConSigno)
                .concepto(concepto)
                .usuario(usuario)
                .venta(venta)
                .fecha(Fechas.ahora())
                .build());
    }
}
