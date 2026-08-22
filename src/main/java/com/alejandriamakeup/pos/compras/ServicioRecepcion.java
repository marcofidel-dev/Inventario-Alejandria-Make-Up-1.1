package com.alejandriamakeup.pos.compras;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.compras.dto.CompraDto;
import com.alejandriamakeup.pos.compras.dto.PreviaAnulacionDto;
import com.alejandriamakeup.pos.compras.dto.PreviaRecepcionDto;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.dinero.Margen;
import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.ServicioCostoPromedio;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * El momento en que una compra deja de ser papel y entra al inventario, y el
 * momento en que se deshace.
 *
 * <p>Los dos escriben en el ledger append-only y después llaman a
 * {@link ServicioCostoPromedio#recalcular(long)}. Que sea la misma función en los
 * dos caminos es lo que impide que recibir y anular se vayan separando: no hay una
 * fórmula "hacia adelante" y otra "hacia atrás" que alguien tenga que mantener
 * sincronizadas.
 */
@Service
@Transactional(readOnly = true)
public class ServicioRecepcion {

    private static final Logger log = LoggerFactory.getLogger(ServicioRecepcion.class);

    private final ServicioCompra servicioCompra;
    private final ServicioVariante servicioVariante;
    private final ServicioCostoPromedio servicioCostoPromedio;
    private final MovimientoInventarioRepository movimientoRepository;
    private final CompraRepository compraRepository;
    private final UsuarioRepository usuarioRepository;

    public ServicioRecepcion(ServicioCompra servicioCompra,
                             ServicioVariante servicioVariante,
                             ServicioCostoPromedio servicioCostoPromedio,
                             MovimientoInventarioRepository movimientoRepository,
                             CompraRepository compraRepository,
                             UsuarioRepository usuarioRepository) {
        this.servicioCompra = servicioCompra;
        this.servicioVariante = servicioVariante;
        this.servicioCostoPromedio = servicioCostoPromedio;
        this.movimientoRepository = movimientoRepository;
        this.compraRepository = compraRepository;
        this.usuarioRepository = usuarioRepository;
    }

    // ------------------------------------------------------------------ previas

    /**
     * Qué pasaría si esta compra se recibiera ahora mismo.
     *
     * <p>Se recorre línea por línea arrastrando el stock y el promedio de cada
     * variante, en vez de agrupar: es exactamente lo que va a hacer la recepción, que
     * escribe un movimiento por línea. Si una factura trae el mismo tono en dos
     * lotes a precios distintos, la previa muestra el mismo encadenamiento que
     * después va a quedar en el ledger.
     */
    public PreviaRecepcionDto previaDeRecepcion(long compraId) {
        Compra compra = servicioCompra.buscarEntidad(compraId);
        exigirEstado(compra, EstadoCompra.BORRADOR,
                "Solo se puede recibir una compra en borrador.");

        List<CompraItem> items = servicioCompra.itemsDe(compraId);
        exigirConLineas(compra, items);

        Map<Long, long[]> corriente = new HashMap<>();   // varianteId -> {stock, promedio}
        List<PreviaRecepcionDto.LineaDto> lineas = new ArrayList<>();
        boolean hayAdvertencias = false;

        for (CompraItem item : items) {
            long varianteId = item.getVariante().getId();
            Variante variante = servicioVariante.buscarEntidad(varianteId);

            long[] estado = corriente.computeIfAbsent(varianteId, id -> new long[] {
                    movimientoRepository.stockDe(id), variante.getCostoPromedio() });

            long stockActual = estado[0];
            long promedioActual = estado[1];

            long promedioResultante = ServicioCostoPromedio.aplicarEntrada(
                    stockActual, promedioActual, item.getCantidad(), item.getCostoUnitario());
            long stockResultante = stockActual + item.getCantidad();

            long precio = variante.getPrecioVenta();
            boolean costoSuperaPrecio = promedioResultante > precio;
            boolean margenBajo = Margen.porDebajoDelMinimo(precio, promedioResultante);

            PreviaRecepcionDto.LineaDto linea = new PreviaRecepcionDto.LineaDto(
                    varianteId, item.getCantidad(), stockActual, stockResultante,
                    item.getCostoUnitario(), promedioActual, promedioResultante, precio,
                    porcentajeParaMostrar(precio, promedioResultante),
                    costoSuperaPrecio, margenBajo);

            lineas.add(linea);
            hayAdvertencias = hayAdvertencias || linea.tieneAdvertencia();

            estado[0] = stockResultante;
            estado[1] = promedioResultante;
        }

        return new PreviaRecepcionDto(compra.getId(), compra.getConsecutivo(), compra.getTotal(),
                lineas, hayAdvertencias);
    }

    /**
     * Qué pasaría si esta compra recibida se anulara.
     *
     * <p>Aquí sí se agrupa por variante: el stock es aditivo y lo que le importa a
     * quien va a confirmar es en cuánto queda cada variante, no cuántas líneas de la
     * factura la mencionaban.
     */
    public PreviaAnulacionDto previaDeAnulacion(long compraId) {
        Compra compra = servicioCompra.buscarEntidad(compraId);
        exigirEstado(compra, EstadoCompra.RECIBIDA,
                "Solo se puede anular una compra recibida.");

        Map<Long, Integer> porVariante = new LinkedHashMap<>();
        for (CompraItem item : servicioCompra.itemsDe(compraId)) {
            porVariante.merge(item.getVariante().getId(), item.getCantidad(), Integer::sum);
        }

        List<PreviaAnulacionDto.LineaDto> lineas = new ArrayList<>();
        boolean hayStockNegativo = false;

        for (Map.Entry<Long, Integer> entrada : porVariante.entrySet()) {
            long stockActual = movimientoRepository.stockDe(entrada.getKey());
            long stockResultante = stockActual - entrada.getValue();
            boolean negativo = stockResultante < 0;

            lineas.add(new PreviaAnulacionDto.LineaDto(entrada.getKey(), entrada.getValue(),
                    stockActual, stockResultante, negativo));
            hayStockNegativo = hayStockNegativo || negativo;
        }

        return new PreviaAnulacionDto(compra.getId(), compra.getConsecutivo(), compra.getTotal(),
                lineas, hayStockNegativo);
    }

    // ------------------------------------------------------------------ recibir

    /**
     * Recibe la compra: un movimiento de entrada por línea y el costo promedio
     * recalculado.
     *
     * <p>Exigir BORRADOR es lo que impide la doble recepción. Dos peticiones seguidas
     * no duplican los movimientos: la segunda encuentra la compra en RECIBIDA y sale
     * con 409. Es una comprobación barata que evita el fallo más caro del módulo,
     * porque un inventario inflado no se nota hasta que alguien cuenta.
     */
    @Transactional
    public CompraDto recibir(long compraId, long usuarioId) {
        Compra compra = servicioCompra.buscarEntidad(compraId);
        exigirEstado(compra, EstadoCompra.BORRADOR,
                "Una compra solo se recibe una vez.");

        List<CompraItem> items = servicioCompra.itemsDe(compraId);
        exigirConLineas(compra, items);

        Usuario usuario = usuario(usuarioId);
        LocalDateTime cuando = Fechas.ahora();
        Set<Long> variantes = new LinkedHashSet<>();

        for (CompraItem item : items) {
            movimientoRepository.save(MovimientoInventario.builder()
                    .variante(item.getVariante())
                    .tipo(TipoMovimientoInventario.COMPRA)
                    .cantidad(item.getCantidad())
                    .costoUnitario(item.getCostoUnitario())
                    .compra(compra)
                    .usuario(usuario)
                    .fecha(cuando)
                    .motivo("Recepción de la compra " + compra.getConsecutivo())
                    .build());
            variantes.add(item.getVariante().getId());
        }

        compra.setEstado(EstadoCompra.RECIBIDA);
        compra.setFechaRecepcion(cuando);
        compraRepository.save(compra);

        variantes.forEach(servicioCostoPromedio::recalcular);

        log.info("Compra {} recibida: {} línea(s), {} variante(s) revaluadas",
                compra.getConsecutivo(), items.size(), variantes.size());
        return CompraDto.de(compra, items);
    }

    /**
     * Anula una compra recibida devolviendo el stock.
     *
     * <p><strong>El orden importa.</strong> El estado pasa a ANULADA
     * <em>antes</em> de recalcular, porque el recálculo salta los movimientos de las
     * compras anuladas y para eso tiene que verla ya anulada. Al revés, el replay
     * trataría esta compra como vigente y dejaría guardado un promedio que incluye
     * mercancía que se acaba de declarar inexistente.
     *
     * <p>El stock puede quedar negativo si parte de la mercancía ya se vendió. No se
     * bloquea: si la compra nunca llegó, el negativo es información verdadera y dice
     * que salió de la vitrina algo que el sistema no tenía. Taparlo sería perder el
     * único rastro de que hay algo que averiguar.
     */
    @Transactional
    public CompraDto anular(long compraId, String motivo, long usuarioId) {
        Compra compra = servicioCompra.buscarEntidad(compraId);
        exigirEstado(compra, EstadoCompra.RECIBIDA,
                "Solo se puede anular una compra recibida. Un borrador se descarta, que es "
                        + "distinto: no hay nada que devolver.");

        List<CompraItem> items = servicioCompra.itemsDe(compraId);
        Usuario usuario = usuario(usuarioId);
        LocalDateTime cuando = Fechas.ahora();
        Set<Long> variantes = new LinkedHashSet<>();

        for (CompraItem item : items) {
            movimientoRepository.save(MovimientoInventario.builder()
                    .variante(item.getVariante())
                    .tipo(TipoMovimientoInventario.ANULACION)
                    .cantidad(-item.getCantidad())
                    .costoUnitario(item.getCostoUnitario())
                    .compra(compra)
                    .usuario(usuario)
                    .fecha(cuando)
                    .motivo("Anulación de la compra " + compra.getConsecutivo() + ": " + motivo)
                    .build());
            variantes.add(item.getVariante().getId());
        }

        compra.darDeBaja(EstadoCompra.ANULADA, motivo.strip(), usuario, cuando);
        compraRepository.save(compra);

        variantes.forEach(servicioCostoPromedio::recalcular);

        log.info("Compra {} anulada: {}", compra.getConsecutivo(), motivo);
        return CompraDto.de(compra, items);
    }

    // ------------------------------------------------------------------ cálculos

    /**
     * El porcentaje que espera el DTO. {@link Margen#porcentaje} devuelve {@code null}
     * cuando no hay precio contra el cual calcularlo; aquí eso se muestra como 0
     * porque la línea ya viene marcada con {@code margenBajo}, que es la que decide.
     */
    private int porcentajeParaMostrar(long precio, long costo) {
        return Objects.requireNonNullElse(Margen.porcentaje(precio, costo), 0);
    }

    private void exigirConLineas(Compra compra, List<CompraItem> items) {
        if (items.isEmpty()) {
            throw ErrorDeAplicacion.conflicto("COMPRA_SIN_LINEAS",
                    "La compra " + compra.getConsecutivo() + " no tiene líneas. Una compra sin "
                            + "líneas no mueve inventario.");
        }
    }

    private void exigirEstado(Compra compra, EstadoCompra esperado, String explicacion) {
        if (compra.getEstado() != esperado) {
            throw ErrorDeAplicacion.conflicto("ESTADO_DE_COMPRA_INVALIDO",
                    "La compra " + compra.getConsecutivo() + " está en estado "
                            + compra.getEstado() + ". " + explicacion);
        }
    }

    private Usuario usuario(long usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(() ->
                ErrorDeAplicacion.noEncontrado("No existe el usuario " + usuarioId));
    }
}
