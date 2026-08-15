package com.alejandriamakeup.pos.caja;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Notas de sesión. Solo lectura y append: la entidad es inmutable.
 */
public interface NotaSesionCajaRepository extends JpaRepository<NotaSesionCaja, Long> {

    List<NotaSesionCaja> findBySesionIdOrderByFechaAsc(Long sesionId);

    /**
     * Las notas de varias sesiones de una vez.
     *
     * <p>Para el historial: una consulta para todo el listado, no una por fila. Al
     * año son unas trescientas sesiones y el listado se pinta entero.
     */
    List<NotaSesionCaja> findBySesionIdInOrderByFechaAsc(Collection<Long> sesionIds);
}
