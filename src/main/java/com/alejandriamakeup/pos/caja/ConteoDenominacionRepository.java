package com.alejandriamakeup.pos.caja;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConteoDenominacionRepository extends JpaRepository<ConteoDenominacion, Long> {

    List<ConteoDenominacion> findBySesionIdOrderByDenominacionDesc(Long sesionId);

    /** El total físico contado: suma de {@code denominacion * cantidad}. */
    @Query("select coalesce(sum(c.denominacion * c.cantidad), 0) from ConteoDenominacion c "
            + "where c.sesion.id = :sesionId")
    long totalContadoDe(@Param("sesionId") Long sesionId);
}
