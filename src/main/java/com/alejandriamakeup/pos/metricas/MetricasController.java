package com.alejandriamakeup.pos.metricas;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.metricas.dto.MetricasDto;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * La API de métricas. <strong>El módulo entero es de la DUENA</strong>: no hay una
 * sola respuesta aquí que no lleve costos o márgenes, así que las tres rutas exigen
 * {@code VER_METRICAS} y no hay ninguna que se quede en "basta con estar
 * autenticado".
 *
 * <p>Sin pantalla todavía — esa es la fase siguiente.
 */
@RestController
@RequestMapping("/api/v1/metricas")
public class MetricasController {

    /**
     * Lo máximo que se puede pedir de una vez.
     *
     * <p>Las métricas de venta se pliegan en memoria, y eso es correcto para un día o
     * un mes. Hoy ningún {@link Periodo} llega ni cerca del tope —un mes y su mes
     * anterior son sesenta y dos días—, así que este corte no se dispara nunca con los
     * parámetros que existen. Está para el día que alguien agregue un rango libre o
     * suba {@code dias}: la única consulta sin tope del sistema sería justo la que
     * cuelga la pantalla, y encontrarlo entonces cuesta mucho más que ponerlo ahora.
     */
    public static final int MAXIMO_DIAS = 366;

    private final ServicioMetricas servicioMetricas;

    public MetricasController(ServicioMetricas servicioMetricas) {
        this.servicioMetricas = servicioMetricas;
    }

    /**
     * El panel completo: resumen del periodo, comparativo contra el anterior, ventas
     * por método de pago, los dos rankings, inventario a costo y vencimientos.
     *
     * <p>Una sola llamada a propósito. Con pool de una conexión, una pantalla que
     * dispara nueve consultas pesadas las espera en fila y se siente lenta.
     *
     * <p>Sin {@code fecha}, hoy — que es lo que se pide casi siempre y ahorra que la
     * pantalla tenga que calcular la fecha local.
     */
    @GetMapping("/panel")
    public MetricasDto.Panel panel(
            @RequestParam(defaultValue = "DIA") Periodo periodo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {

        LocalDate dia = fecha == null ? LocalDate.now() : fecha;
        Periodo.Rango actual = periodo.rangoDe(dia);
        Periodo.Rango anterior = periodo.anteriorDe(dia);
        exigirRangoAcotado(ChronoUnit.DAYS.between(anterior.desde(), actual.hastaExclusivo()));

        return servicioMetricas.panel(periodo, dia);
    }

    /** Variantes con stock que no se han vendido en {@code dias} días. */
    @GetMapping("/sin-rotacion")
    public MetricasDto.SinRotacion sinRotacion(@RequestParam(defaultValue = "90") int dias) {
        if (dias < 1) {
            throw ErrorDeAplicacion.peticionInvalida(
                    "El número de días tiene que ser al menos 1, y llegó " + dias + ".");
        }
        exigirRangoAcotado(dias);
        return servicioMetricas.sinRotacion(dias);
    }

    /** Lo vencido y lo que vence dentro de los próximos 90 días. */
    @GetMapping("/vencimientos")
    public List<MetricasDto.Vencimiento> vencimientos() {
        return servicioMetricas.vencimientos();
    }

    private void exigirRangoAcotado(long dias) {
        if (dias > MAXIMO_DIAS) {
            throw ErrorDeAplicacion.peticionInvalida(
                    "El rango pedido abarca " + dias + " días y el máximo es " + MAXIMO_DIAS
                            + ". Hay que consultar por periodos más cortos.");
        }
    }
}
