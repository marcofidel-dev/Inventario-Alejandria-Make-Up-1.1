package com.alejandriamakeup.pos.ventas;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VentaRepository extends JpaRepository<Venta, Long> {

    Optional<Venta> findByUuid(String uuid);

    Optional<Venta> findByConsecutivo(String consecutivo);

    List<Venta> findBySesionCajaIdOrderByFechaAsc(Long sesionCajaId);

    List<Venta> findByEstado(EstadoVenta estado);

    List<Venta> findByClienteIdOrderByFechaDesc(Long clienteId);

    /**
     * Ventas en un rango. Funciona porque las fechas se guardan como TEXT
     * ISO-8601 de ancho fijo: comparar y ordenar ese texto equivale a comparar y
     * ordenar cronológicamente.
     */
    List<Venta> findByFechaBetweenOrderByFechaAsc(LocalDateTime desde, LocalDateTime hasta);

    /**
     * Las ventas que se quedaron sin comprobante.
     *
     * <p>Existe porque la generación del PDF puede fallar —disco lleno, permisos— y
     * cuando falla la venta sigue siendo válida y {@code ruta_recibo} queda en nulo.
     * Sin esta consulta, recuperarlas obligaría a revisar el listado día por día
     * buscando cuáles no tienen recibo, y las que fallaron un día que nadie miró no se
     * encontrarían nunca.
     */
    List<Venta> findByRutaReciboIsNullOrderByFechaAsc();

    /**
     * El desglose por método de pago de una sesión, para el cierre.
     *
     * <p>Solo las {@code COMPLETADA}: una venta anulada ya devolvió su plata con un
     * movimiento de caja de signo contrario, así que contarla aquí la sumaría dos
     * veces y el desglose no cuadraría con el efectivo esperado.
     */
    @Query("""
            select new com.alejandriamakeup.pos.ventas.VentasPorMetodo(
                       v.metodoPago, count(v), coalesce(sum(v.total), 0))
            from Venta v
            where v.sesionCaja.id = :sesionId
              and v.estado = com.alejandriamakeup.pos.ventas.EstadoVenta.COMPLETADA
            group by v.metodoPago
            order by v.metodoPago
            """)
    List<VentasPorMetodo> desglosePorMetodo(@Param("sesionId") Long sesionId);
}
