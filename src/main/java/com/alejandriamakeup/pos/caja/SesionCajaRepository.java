package com.alejandriamakeup.pos.caja;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SesionCajaRepository extends JpaRepository<SesionCaja, Long> {

    /**
     * La sesión abierta, si hay alguna. Devuelve {@link Optional} y no una lista
     * porque el índice único parcial {@code ux_sesion_caja_unica_abierta}
     * garantiza que no puede haber dos: la base rechazaría la segunda.
     */
    @Query("select s from SesionCaja s where s.estado = com.alejandriamakeup.pos.caja.EstadoSesionCaja.ABIERTA")
    Optional<SesionCaja> buscarAbierta();

    Optional<SesionCaja> findByConsecutivo(String consecutivo);

    List<SesionCaja> findByEstadoOrderByFechaAperturaDesc(EstadoSesionCaja estado);

    List<SesionCaja> findAllByOrderByFechaAperturaDesc();

    List<SesionCaja> findByUsuarioAperturaIdOrderByFechaAperturaDesc(Long usuarioId);
}
