package com.alejandriamakeup.pos.autenticacion;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Ledger de intentos. Solo lectura y append: la entidad es inmutable. */
public interface IntentoLoginRepository extends JpaRepository<IntentoLogin, Long> {

    /**
     * Cuándo entró bien por última vez, si alguna vez. Se usa como piso para
     * contar los fallos: un inicio de sesión exitoso borra la cuenta anterior.
     */
    @Query("select max(i.fecha) from IntentoLogin i where i.nombre = :nombre and i.exito = true")
    Optional<LocalDateTime> ultimoExitoDe(@Param("nombre") String nombre);

    /**
     * Fallos de este usuario posteriores a {@code desde}. La comparación funciona
     * porque las fechas se guardan como TEXT ISO-8601 de ancho fijo, donde el orden
     * lexicográfico es el cronológico.
     */
    @Query("select count(i) from IntentoLogin i "
            + "where i.nombre = :nombre and i.exito = false and i.fecha > :desde")
    long fallosDesde(@Param("nombre") String nombre, @Param("desde") LocalDateTime desde);

    /** El fallo más antiguo del bloqueo vigente, para saber cuándo se libera. */
    @Query("select min(i.fecha) from IntentoLogin i "
            + "where i.nombre = :nombre and i.exito = false and i.fecha > :desde")
    Optional<LocalDateTime> primerFalloDesde(@Param("nombre") String nombre,
                                             @Param("desde") LocalDateTime desde);
}
