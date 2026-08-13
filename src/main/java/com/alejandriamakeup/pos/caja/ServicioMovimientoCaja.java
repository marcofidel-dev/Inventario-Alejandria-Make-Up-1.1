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
 * parte: se calcula con {@code base_inicial + SUM(monto)} cada vez que hace falta,
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
        if (venta.getMetodoPago() != MetodoPago.EFECTIVO) {
            log.debug("Venta {} por {}: no toca movimiento_caja", venta.getConsecutivo(),
                    venta.getMetodoPago());
            return null;
        }

        return guardar(venta.getSesionCaja(), TipoMovimientoCaja.VENTA_EFECTIVO,
                TipoMovimientoCaja.VENTA_EFECTIVO.conSigno(venta.getTotal()),
                "Venta " + venta.getConsecutivo(), venta.getUsuario().getId(), venta);
    }

    public List<MovimientoCaja> deSesion(long sesionId) {
        return movimientoRepository.findBySesionIdOrderByFechaAsc(sesionId);
    }

    /** La suma con signo de los movimientos. Sin la base inicial: eso lo suma el cierre. */
    public long sumaDe(long sesionId) {
        return movimientoRepository.sumaDe(sesionId);
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
