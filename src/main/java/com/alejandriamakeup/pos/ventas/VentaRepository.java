package com.alejandriamakeup.pos.ventas;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
