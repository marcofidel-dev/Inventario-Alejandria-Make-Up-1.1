package com.alejandriamakeup.pos.caja;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Ledger del cajón. Solo lectura y append: la entidad es inmutable.
 */
public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    List<MovimientoCaja> findBySesionIdOrderByFechaAsc(Long sesionId);

    List<MovimientoCaja> findByVentaId(Long ventaId);

    /**
     * La suma con signo de los movimientos de una sesión. No incluye la base
     * inicial: el efectivo esperado es esta suma más la base, y ese cálculo es del
     * servicio de cierre, no de aquí.
     */
    @Query("select coalesce(sum(m.monto), 0) from MovimientoCaja m where m.sesion.id = :sesionId")
    long sumaDe(@Param("sesionId") Long sesionId);
}
