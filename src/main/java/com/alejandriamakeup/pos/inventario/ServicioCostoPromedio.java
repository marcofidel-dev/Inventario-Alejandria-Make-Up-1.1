package com.alejandriamakeup.pos.inventario;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.compras.EstadoCompra;

/**
 * El costo promedio ponderado de una variante, reconstruido desde el ledger.
 *
 * <h2>Por qué se recalcula en vez de ajustarse</h2>
 *
 * Recibir una compra es fácil de aplicar hacia adelante. Anular una recibida no:
 * hay que deshacer un promedio ponderado que puede llevar encima otras compras y
 * otras ventas posteriores. Cualquier fórmula que intente "restar" esa compra
 * escribe un número que no corresponde a ningún momento real del inventario. Por
 * eso los dos caminos —recibir y anular— terminan llamando a lo mismo:
 * {@link #recalcular(long)} rehace el promedio desde el principio del ledger, que
 * es la única fuente de verdad del sistema.
 *
 * <h2>Por qué es secuencial y no agregado</h2>
 *
 * La tentación es {@code Σ(cantidad × costo) / Σ(cantidad)} sobre las entradas.
 * Está mal, y no por poco. Dos contraejemplos, ambos cubiertos por
 * {@code CostoPromedioSecuencialTest}:
 *
 * <ul>
 *   <li>Compra 10 @ 5.000, venta 8, compra 10 @ 9.000. El agregado da
 *       <strong>7.000</strong>; el correcto es <strong>8.333</strong>. Un 16% de
 *       margen inventado. Las ocho unidades baratas ya se vendieron y no pueden
 *       seguir pesando en el costo de lo que queda.
 *   <li>Compra 10 @ 5.000, venta 3, anular la compra. El agregado divide por cero:
 *       {@code Σ(cantidad)} de la compra y su anulación es 0.
 * </ul>
 *
 * El promedio móvil no tiene ninguno de los dos problemas porque respeta el orden
 * en que pasaron las cosas: una salida consume unidades al promedio vigente y no
 * lo altera, y cada entrada se pondera solo contra lo que de verdad había en ese
 * instante.
 *
 * <h2>Qué significa anular</h2>
 *
 * Una compra ANULADA <strong>nunca ocurrió</strong>. El replay salta sus dos
 * movimientos —el {@code COMPRA} y el {@code ANULACION} que lo compensa— en vez de
 * aplicarlos y después deshacerlos. Como netean a cero, saltarlos no cambia el
 * stock, que sigue coincidiendo con {@code SUM(cantidad)} sobre el ledger. Lo que
 * cambia es el promedio, y ahí está toda la diferencia.
 */
@Service
@Transactional(readOnly = true)
public class ServicioCostoPromedio {

    private static final Logger log = LoggerFactory.getLogger(ServicioCostoPromedio.class);

    private final MovimientoInventarioRepository movimientoRepository;
    private final VarianteRepository varianteRepository;

    public ServicioCostoPromedio(MovimientoInventarioRepository movimientoRepository,
                                 VarianteRepository varianteRepository) {
        this.movimientoRepository = movimientoRepository;
        this.varianteRepository = varianteRepository;
    }

    /**
     * Rehace el promedio de la variante desde el ledger y lo guarda.
     *
     * <p>Se llama después de cada mutación del ledger de esa variante. Que recibir y
     * anular usen exactamente la misma función es lo que impide que los dos caminos
     * se separen.
     *
     * @return el promedio que quedó guardado
     */
    @Transactional
    public long recalcular(long varianteId) {
        long promedio = calcular(varianteId);

        Variante variante = varianteRepository.findById(varianteId).orElseThrow();
        if (variante.getCostoPromedio() != promedio) {
            log.info("Costo promedio de la variante {}: {} -> {}",
                    varianteId, variante.getCostoPromedio(), promedio);
            variante.setCostoPromedio(promedio);
            varianteRepository.save(variante);
        }
        return promedio;
    }

    /**
     * El promedio que corresponde al ledger actual, sin escribir nada.
     *
     * <p>Público porque es lo que permite afirmar en un test que el valor guardado y
     * el que dicta el ledger no se han separado.
     */
    public long calcular(long varianteId) {
        List<MovimientoParaCosteo> historial =
                movimientoRepository.historialParaCosteo(varianteId);

        long stock = 0;
        long promedio = 0;

        for (MovimientoParaCosteo movimiento : historial) {
            if (movimiento.getEstadoCompra() == EstadoCompra.ANULADA) {
                // La compra nunca ocurrió: ni su entrada ni la anulación que la
                // compensa. Netean a cero, así que el stock no se entera.
                continue;
            }

            int cantidad = movimiento.getCantidad();

            if (cantidad < 0) {
                // Una salida consume al promedio vigente y no lo mueve.
                stock += cantidad;
                continue;
            }

            promedio = aplicarEntrada(stock, promedio, cantidad, movimiento.getCostoUnitario());
            stock += cantidad;
        }

        return promedio;
    }

    /**
     * El promedio después de que entren {@code cantidad} unidades a
     * {@code costoUnitario}, partiendo de {@code stock} unidades valoradas a
     * {@code promedio}.
     *
     * <p>Un solo paso del promedio móvil, aislado porque lo necesitan dos sitios: el
     * replay de aquí y la vista previa de la recepción, que muestra el costo que
     * <em>quedaría</em>. Si cada uno llevara su copia de la fórmula, la pantalla
     * podría anunciar un número y el servidor guardar otro.
     *
     * <p>Con stock cero o negativo el promedio anterior no significa nada —no hay
     * unidades que ponderar, y ponderar contra un negativo daría un disparate— así
     * que la entrada fija el costo. El negativo es un caso real desde la Fase 6:
     * anular una compra cuya mercancía ya se vendió deja el stock por debajo de
     * cero.
     */
    public static long aplicarEntrada(long stock, long promedio, int cantidad, long costoUnitario) {
        if (stock <= 0) {
            return costoUnitario;
        }

        long numerador = stock * promedio + (long) cantidad * costoUnitario;
        long denominador = stock + cantidad;

        // Redondeo a la mitad hacia arriba, en cada paso y no solo al final: el
        // dinero es long y arrastrar el truncamiento por veinte movimientos separa
        // el promedio de la realidad peso a peso. Los dos operandos son positivos
        // aquí, así que la forma entera es exacta y no necesita punto flotante.
        return (numerador + denominador / 2) / denominador;
    }
}
