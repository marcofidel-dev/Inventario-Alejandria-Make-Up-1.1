package com.alejandriamakeup.pos.consecutivos;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Las filas las crea la migración V2 y nunca se agregan ni se borran: solo se
 * incrementan, y eso pasa por {@link ServicioConsecutivo}, no por aquí.
 */
public interface ConsecutivoRepository extends JpaRepository<Consecutivo, TipoConsecutivo> {
}
