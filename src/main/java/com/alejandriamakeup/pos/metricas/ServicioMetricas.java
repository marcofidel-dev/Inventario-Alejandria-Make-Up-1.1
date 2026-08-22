package com.alejandriamakeup.pos.metricas;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.catalogo.Descripcion;
import com.alejandriamakeup.pos.catalogo.VarianteMetricaFila;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.dinero.Margen;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.StockPorVariante;
import com.alejandriamakeup.pos.metricas.dto.MetricasDto;
import com.alejandriamakeup.pos.ventas.LineaVendida;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.VentaItemRepository;
import com.alejandriamakeup.pos.ventas.VentasPorMetodo;

/**
 * Las métricas de la DUENA: costos y márgenes de principio a fin.
 *
 * <h2>Una sola fuente de líneas vendidas</h2>
 *
 * Nada de aquí escribe SQL de ventas. Todo lo que tenga que ver con lo vendido sale
 * de {@link VentaItemRepository#lineasVendidas}, que ya aplicó los dos filtros que
 * hacen verdadero el número —solo COMPLETADA, e ingreso neto de descuento— y
 * devuelve {@link LineaVendida}, un record del que ni siquiera se puede sacar el
 * subtotal crudo. Con nueve consultas que recordaran los filtros por su cuenta, la
 * que se olvidara de uno devolvería un margen alto y creíble, y nadie lo revisaría.
 *
 * <h2>Se pliega en memoria</h2>
 *
 * Una consulta trae las líneas del periodo y todas las métricas de venta se calculan
 * recorriéndolas. No es pereza: con pool de una conexión las consultas se serializan,
 * así que nueve agregaciones en SQL son nueve esperas en fila, y una pantalla que
 * tarda tres segundos en abrir. El volumen lo permite —una tienda de barrio hace del
 * orden de diez mil líneas al año— y el controlador acota el rango a un año para que
 * eso siga siendo cierto el día que alguien agregue un rango libre.
 *
 * <h2>Nada de esto mira el catálogo</h2>
 *
 * Precio, costo y descripción vienen congelados de {@code venta_item}. Cambiar un
 * precio hoy no puede mover el margen de lo que se vendió el año pasado; esa es
 * exactamente la razón de que esos campos existan.
 *
 * <h2>PAO</h2>
 *
 * {@code pao_meses} viaja en las filas de vencimiento como dato informativo y no se
 * calcula nada con él. El PAO cuenta desde que el envase se <em>abre</em>, y el
 * sistema no registra en ningún sitio cuándo se abre un producto — sin ese dato no
 * hay fecha desde la cual contar. Anotado en la especificación §11.2 como pendiente
 * con lo que le falta, para que sea una decisión y no un olvido.
 */
@Service
@Transactional(readOnly = true)
public class ServicioMetricas {

    /**
     * Cuántos productos entran en cada ranking. Diez porque es lo que se mira de un
     * vistazo: una lista de cincuenta no se lee, se hojea.
     */
    public static final int TOPE_DEL_RANKING = 10;

    /** Hasta dónde llega la alerta de vencimiento. Más allá no es accionable. */
    public static final int HORIZONTE_DE_VENCIMIENTO_DIAS = 90;

    private final VentaItemRepository itemRepository;
    private final VarianteRepository varianteRepository;
    private final MovimientoInventarioRepository movimientoRepository;

    public ServicioMetricas(VentaItemRepository itemRepository,
                            VarianteRepository varianteRepository,
                            MovimientoInventarioRepository movimientoRepository) {
        this.itemRepository = itemRepository;
        this.varianteRepository = varianteRepository;
        this.movimientoRepository = movimientoRepository;
    }

    // ------------------------------------------------------------------ panel

    /**
     * El panel completo en tres consultas: las líneas vendidas, las variantes y el
     * stock. Fijas — no crecen con el número de productos ni de ventas.
     *
     * <p>El periodo actual y el anterior salen de <strong>una sola</strong> lectura
     * sobre el rango que los une, y se parten en memoria. Son contiguos por
     * construcción, así que no hay hueco entre los dos.
     */
    public MetricasDto.Panel panel(Periodo periodo, LocalDate fecha) {
        Periodo.Rango actual = periodo.rangoDe(fecha);
        Periodo.Rango anterior = periodo.anteriorDe(fecha);

        List<LineaVendida> lineas = itemRepository.lineasVendidas(
                anterior.desde().atStartOfDay(), actual.hastaExclusivo().atStartOfDay());

        List<LineaVendida> deActual = enRango(lineas, actual);
        List<LineaVendida> deAnterior = enRango(lineas, anterior);

        Existencias existencias = existencias();
        List<MetricasDto.ProductoVendido> ranking = ranking(deActual);

        return new MetricasDto.Panel(
                MetricasDto.Agrupacion.FECHA_DE_VENTA,
                rangoDto(periodo, actual),
                rangoDto(periodo, anterior),
                resumir(deActual),
                resumir(deAnterior),
                porMetodoDePago(deActual),
                primeros(ranking, Comparator.comparingLong(MetricasDto.ProductoVendido::unidades)),
                primeros(ranking, Comparator.comparingLong(MetricasDto.ProductoVendido::margen)),
                existencias.inventario(),
                existencias.vencimientos(LocalDate.now()));
    }

    // ------------------------------------------------------------------ detalles

    /**
     * Variantes con stock que no se vendieron en los últimos {@code dias} días.
     *
     * <p>La respuesta lleva la aclaración de lo que <em>no</em> dice, y la lleva como
     * campo para que la pantalla la muestre tal cual: aquí no se distingue el producto
     * que se vendía y dejó de venderse del que nunca se vendió, y son dos problemas
     * distintos —uno perdió su clientela, el otro nunca la tuvo—. Saberlo costaría
     * recorrer la historia completa de ventas de cada variante, y la pregunta que
     * responde esta lista no lo necesita.
     */
    public MetricasDto.SinRotacion sinRotacion(int dias) {
        LocalDate hoy = LocalDate.now();
        Set<Long> vendidas = itemRepository
                .lineasVendidas(hoy.minusDays(dias).atStartOfDay(), hoy.plusDays(1).atStartOfDay())
                .stream()
                .map(LineaVendida::varianteId)
                .collect(Collectors.toCollection(HashSet::new));

        Existencias existencias = existencias();
        List<MetricasDto.VarianteQuieta> filas = new ArrayList<>();

        for (VarianteMetricaFila variante : existencias.variantes()) {
            long stock = existencias.stockDe(variante.getId());
            if (stock <= 0 || vendidas.contains(variante.getId())) {
                continue;
            }
            filas.add(new MetricasDto.VarianteQuieta(
                    variante.getId(), describir(variante), stock, variante.getCostoPromedio(),
                    stock * variante.getCostoPromedio()));
        }

        filas.sort(Comparator.comparingLong(MetricasDto.VarianteQuieta::valorACosto).reversed());

        return new MetricasDto.SinRotacion(dias, MetricasDto.Agrupacion.FECHA_DE_VENTA,
                "Estas variantes tienen stock y no registran ventas en los últimos " + dias
                        + " días. La lista no dice si se vendieron antes: un producto que se "
                        + "vendía y dejó de venderse aparece igual que uno que nunca se ha "
                        + "vendido, y son cosas distintas.",
                filas);
    }

    /**
     * Lo vencido y lo que vence dentro del horizonte, de lo más urgente a lo menos.
     *
     * <p><strong>Se compara contra {@link LocalDate}, nunca contra un instante.</strong>
     * {@code fecha_vencimiento} es una fecha sin hora; contrastarla con un
     * {@code datetime} pondría "vencido" un producto que vence hoy, en cuanto pasara
     * la medianoche — y hoy todavía se puede vender. Aquí no hay forma de que ocurra:
     * la comparación es entre dos {@link LocalDate} y no hay ninguna función de fecha
     * en el SQL de este módulo.
     *
     * <p>Solo con stock: una variante agotada que vence no es una pérdida, no queda
     * nada que botar. Se incluyen las desactivadas — el frasco sigue en el estante
     * aunque nadie lo esté vendiendo.
     */
    public List<MetricasDto.Vencimiento> vencimientos() {
        LocalDate hoy = LocalDate.now();
        Existencias existencias = existencias();
        List<MetricasDto.Vencimiento> filas = new ArrayList<>();

        for (VarianteMetricaFila variante : existencias.variantes()) {
            LocalDate vence = variante.getFechaVencimiento();
            long stock = existencias.stockDe(variante.getId());
            if (vence == null || stock <= 0) {
                continue;
            }

            long dias = diasEntre(hoy, vence);
            if (dias > HORIZONTE_DE_VENCIMIENTO_DIAS) {
                continue;
            }

            filas.add(new MetricasDto.Vencimiento(
                    variante.getId(), describir(variante), vence, dias, stock,
                    stock * variante.getCostoPromedio(), variante.getPaoMeses()));
        }

        filas.sort(Comparator.comparing(MetricasDto.Vencimiento::fechaVencimiento));
        return filas;
    }

    // ------------------------------------------------------------------ plegado

    private static List<LineaVendida> enRango(List<LineaVendida> lineas, Periodo.Rango rango) {
        return lineas.stream()
                .filter(linea -> {
                    LocalDate dia = linea.fecha().toLocalDate();
                    return !dia.isBefore(rango.desde()) && dia.isBefore(rango.hastaExclusivo());
                })
                .toList();
    }

    private static MetricasDto.Resumen resumir(List<LineaVendida> lineas) {
        long unidades = 0;
        long ingreso = 0;
        long costo = 0;
        Set<Long> ventas = new HashSet<>();

        for (LineaVendida linea : lineas) {
            ventas.add(linea.ventaId());
            unidades += linea.cantidad();
            ingreso += linea.ingreso();
            costo += linea.costo();
        }

        return new MetricasDto.Resumen(ventas.size(), unidades, ingreso, costo,
                Margen.de(ingreso, costo), Margen.porcentaje(ingreso, costo));
    }

    /**
     * El mismo desglose que muestra el arqueo, con la otra agrupación.
     *
     * <p>Devuelve {@link VentasPorMetodo}, el mismo tipo que usa caja, y no un record
     * paralelo: cuando una sesión abre y cierra el mismo día las dos listas tienen que
     * salir idénticas, y compartir el tipo hace que compararlas sea trivial en vez de
     * un mapeo campo a campo que puede mentir. {@code cantidad} son ventas, no líneas,
     * que es lo que cuenta caja.
     */
    private static List<VentasPorMetodo> porMetodoDePago(List<LineaVendida> lineas) {
        Map<MetodoPago, Set<Long>> ventas = new LinkedHashMap<>();
        Map<MetodoPago, Long> totales = new LinkedHashMap<>();

        for (LineaVendida linea : lineas) {
            ventas.computeIfAbsent(linea.metodoPago(), metodo -> new HashSet<>())
                    .add(linea.ventaId());
            totales.merge(linea.metodoPago(), linea.ingreso(), Long::sum);
        }

        return ventas.keySet().stream()
                .sorted()
                .map(metodo -> new VentasPorMetodo(
                        metodo, ventas.get(metodo).size(), totales.get(metodo)))
                .toList();
    }

    /** Un acumulado por variante, del que salen los dos rankings. */
    private record Acumulado(String descripcion, long unidades, long ingreso, long costo) {

        Acumulado mas(Acumulado otro) {
            return new Acumulado(descripcion, unidades + otro.unidades(),
                    ingreso + otro.ingreso(), costo + otro.costo());
        }
    }

    private static List<MetricasDto.ProductoVendido> ranking(List<LineaVendida> lineas) {
        Map<Long, Acumulado> porVariante = new LinkedHashMap<>();

        for (LineaVendida linea : lineas) {
            porVariante.merge(linea.varianteId(),
                    new Acumulado(linea.descripcion(), linea.cantidad(), linea.ingreso(),
                            linea.costo()),
                    Acumulado::mas);
        }

        return porVariante.entrySet().stream()
                .map(entrada -> {
                    Acumulado acumulado = entrada.getValue();
                    return new MetricasDto.ProductoVendido(entrada.getKey(),
                            acumulado.descripcion(), acumulado.unidades(), acumulado.ingreso(),
                            acumulado.costo(),
                            Margen.de(acumulado.ingreso(), acumulado.costo()),
                            Margen.porcentaje(acumulado.ingreso(), acumulado.costo()));
                })
                .toList();
    }

    /**
     * Los primeros del ranking según el criterio que se le pase.
     *
     * <p>Dos listas del mismo material y no una: lo que más sale no siempre es lo que
     * más deja, y las dos importan. Se desempata por descripción para que dos
     * productos con el mismo número no se intercambien de posición entre llamadas.
     */
    private static List<MetricasDto.ProductoVendido> primeros(
            List<MetricasDto.ProductoVendido> ranking,
            Comparator<MetricasDto.ProductoVendido> criterio) {
        return ranking.stream()
                .sorted(criterio.reversed()
                        .thenComparing(MetricasDto.ProductoVendido::descripcion))
                .limit(TOPE_DEL_RANKING)
                .toList();
    }

    // ------------------------------------------------------------------ existencias

    /** Variantes y stock: las dos consultas que no vienen del ledger de ventas. */
    private Existencias existencias() {
        List<VarianteMetricaFila> variantes = varianteRepository.filasParaMetricas();

        Map<Long, Long> stock = new HashMap<>();
        for (StockPorVariante fila : movimientoRepository.stockDeTodas()) {
            stock.put(fila.getVarianteId(), fila.getStock());
        }

        return new Existencias(variantes, stock);
    }

    private record Existencias(List<VarianteMetricaFila> variantes, Map<Long, Long> stockPorId) {

        /** Una variante sin ningún movimiento no aparece en el agrupado: es stock 0. */
        long stockDe(Long varianteId) {
            return stockPorId.getOrDefault(varianteId, 0L);
        }

        MetricasDto.Inventario inventario() {
            long valor = 0;
            long unidades = 0;
            List<MetricasDto.StockBajo> bajoMinimo = new ArrayList<>();

            for (VarianteMetricaFila variante : variantes) {
                long stock = stockDe(variante.getId());
                valor += stock * variante.getCostoPromedio();
                unidades += stock;

                // Mismo criterio que VarianteRepository.bajoMinimo(), y hay un test que
                // compara los dos conjuntos completos. La regla queda escrita dos veces
                // —aquí y en SQL— a sabiendas: volver a consultarla costaría una consulta
                // más y un N+1 al pedir las descripciones.
                if (variante.isActivo() && stock < variante.getStockMinimo()) {
                    bajoMinimo.add(new MetricasDto.StockBajo(variante.getId(),
                            describir(variante), stock, variante.getStockMinimo()));
                }
            }

            return new MetricasDto.Inventario(valor, unidades, bajoMinimo);
        }

        MetricasDto.Vencimientos vencimientos(LocalDate hoy) {
            long vencidos = 0;
            long hasta30 = 0;
            long entre31y60 = 0;
            long entre61y90 = 0;

            for (VarianteMetricaFila variante : variantes) {
                LocalDate vence = variante.getFechaVencimiento();
                if (vence == null || stockDe(variante.getId()) <= 0) {
                    continue;
                }

                long dias = diasEntre(hoy, vence);
                if (dias < 0) {
                    vencidos++;
                } else if (dias <= 30) {
                    hasta30++;
                } else if (dias <= 60) {
                    entre31y60++;
                } else if (dias <= HORIZONTE_DE_VENCIMIENTO_DIAS) {
                    entre61y90++;
                }
            }

            return new MetricasDto.Vencimientos(vencidos, hasta30, entre31y60, entre61y90);
        }
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Días entre dos fechas. Entre dos {@link LocalDate} y punto: si alguno de los dos
     * lados fuera un instante, un producto que vence hoy contaría como vencido desde
     * las 00:00:01.
     */
    private static long diasEntre(LocalDate desde, LocalDate hasta) {
        return ChronoUnit.DAYS.between(desde, hasta);
    }

    private static String describir(VarianteMetricaFila variante) {
        return Descripcion.de(variante.getMarcaNombre(), variante.getProductoNombre(),
                variante.getTono(), variante.getTamano());
    }

    private static MetricasDto.RangoDto rangoDto(Periodo periodo, Periodo.Rango rango) {
        return new MetricasDto.RangoDto(periodo, rango.desde(), rango.hastaInclusivo());
    }
}
