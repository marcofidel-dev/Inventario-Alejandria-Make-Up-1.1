package com.alejandriamakeup.pos.metricas.dto;

import java.time.LocalDate;
import java.util.List;

import com.alejandriamakeup.pos.metricas.Periodo;
import com.alejandriamakeup.pos.ventas.VentasPorMetodo;

/**
 * Las respuestas del módulo de métricas. Anidadas en un solo archivo porque son un
 * solo contrato: se leen de corrido y cambian juntas.
 */
public final class MetricasDto {

    private MetricasDto() {
    }

    /**
     * Cómo se agruparon las ventas de una métrica. <strong>Va en la respuesta, no en
     * un comentario</strong>, porque la pantalla tiene que decirlo.
     *
     * <p>Las dos agrupaciones dan números distintos y ninguna está mal: una sesión de
     * caja que se abrió el lunes y se cerró el martes contiene ventas de dos fechas.
     * Si el informe del lunes no cuadra con el arqueo del lunes, quien mira tiene que
     * poder entender por qué sin llamar a nadie.
     */
    public enum Agrupacion {

        /** Por la fecha de la venta. Es la de todo este módulo. */
        FECHA_DE_VENTA,

        /** Por la sesión de caja que la contiene. Es la del arqueo, en el módulo de caja. */
        SESION_DE_CAJA
    }

    /**
     * El panel completo, en una sola llamada y tres consultas.
     *
     * <p>Una pantalla que dispara nueve consultas pesadas con pool de una conexión se
     * siente lenta, y no hay forma de esconderlo: las consultas se serializan.
     *
     * @param agrupacion siempre {@link Agrupacion#FECHA_DE_VENTA} aquí, y declarado
     *        igual — un valor constante que la pantalla lee es más difícil de olvidar
     *        que uno que se da por sabido
     */
    public record Panel(
            Agrupacion agrupacion,
            RangoDto periodo,
            RangoDto periodoAnterior,
            Resumen actual,
            Resumen anterior,
            List<VentasPorMetodo> porMetodoPago,
            List<ProductoVendido> masVendidosPorUnidades,
            List<ProductoVendido> masVendidosPorMargen,
            Inventario inventario,
            Vencimientos vencimientos) {
    }

    /** Qué periodo se midió, con las dos puntas inclusivas para mostrarlas tal cual. */
    public record RangoDto(Periodo tipo, LocalDate desde, LocalDate hasta) {
    }

    /**
     * Lo que se vendió en un periodo.
     *
     * @param ventas número de ventas distintas, no de líneas
     * @param ingreso ya neto de descuento
     * @param margenPorcentaje sobre precio de venta; {@code null} si no hubo ingreso
     */
    public record Resumen(
            long ventas,
            long unidades,
            long ingreso,
            long costo,
            long margen,
            Integer margenPorcentaje) {
    }

    /**
     * Un producto en el ranking.
     *
     * <p>Hay dos rankings y no uno porque no son la misma pregunta: lo que más sale
     * no siempre es lo que más deja. El delineador barato puede encabezar por
     * unidades y aportar menos margen que tres bases vendidas en el mes.
     *
     * <p>{@code descripcion} es la congelada de {@code venta_item}: dice cómo se
     * llamaba el producto cuando se vendió.
     */
    public record ProductoVendido(
            Long varianteId,
            String descripcion,
            long unidades,
            long ingreso,
            long costo,
            long margen,
            Integer margenPorcentaje) {
    }

    /**
     * @param valorACosto {@code SUM(stock × costo_promedio)} sobre todas las
     *        variantes. Una con stock negativo <strong>resta</strong>, y eso es
     *        verdad: dice que se vendió mercancía que nunca entró
     * @param variantesBajoMinimo mismo criterio que {@code VarianteRepository.bajoMinimo()}
     */
    public record Inventario(
            long valorACosto,
            long unidades,
            List<StockBajo> variantesBajoMinimo) {
    }

    public record StockBajo(
            Long varianteId,
            String descripcion,
            long stock,
            int stockMinimo) {
    }

    /**
     * Conteo por tramo. <strong>Los tramos no se solapan</strong>: se nombran por sus
     * bordes justamente para que "por vencer en 60 días" no signifique una cosa aquí
     * y otra en la pantalla.
     *
     * <p>Un producto que vence <em>hoy</em> cuenta en {@code hasta30}, no en
     * {@code vencidos}: todavía se puede vender.
     */
    public record Vencimientos(
            long vencidos,
            long hasta30,
            long entre31y60,
            long entre61y90) {
    }

    /**
     * @param diasParaVencer negativo si ya venció
     * @param valorACosto lo que se pierde si vence sin venderse. En maquillaje un
     *        producto vencido es pérdida total: no se rebaja, se bota
     * @param paoMeses informativo. Ver {@code ServicioMetricas} sobre por qué hoy no
     *        se puede calcular nada con él
     */
    public record Vencimiento(
            Long varianteId,
            String descripcion,
            LocalDate fechaVencimiento,
            long diasParaVencer,
            long stock,
            long valorACosto,
            Integer paoMeses) {
    }

    /**
     * @param aclaracion se muestra tal cual. Existe porque la lista responde una
     *        pregunta más estrecha que la que parece
     */
    public record SinRotacion(
            int dias,
            Agrupacion agrupacion,
            String aclaracion,
            List<VarianteQuieta> filas) {
    }

    public record VarianteQuieta(
            Long varianteId,
            String descripcion,
            long stock,
            long costoPromedio,
            long valorACosto) {
    }
}
