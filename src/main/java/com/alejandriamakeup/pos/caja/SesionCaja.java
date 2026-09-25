package com.alejandriamakeup.pos.caja;

import java.time.LocalDateTime;

import com.alejandriamakeup.pos.usuarios.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Una sesión de caja. Solo puede haber una ABIERTA a la vez en todo el sistema,
 * regla que impone el índice único parcial
 * {@code ux_sesion_caja_unica_abierta}, no el código.
 *
 * <p>{@code efectivoEsperado}, {@code efectivoContado} y {@code diferencia} son
 * nulos mientras la sesión está abierta y se congelan al cerrar: no se
 * recalculan nunca más. El cierre es <strong>a ciegas</strong> — primero entra
 * el conteo físico y solo después el sistema revela esperado y diferencia — pero
 * eso lo hará el servicio de cierre, no esta entidad.
 *
 * <p>{@code diferencia} puede ser negativa (faltó plata), así que no lleva CHECK
 * de signo en el esquema.
 */
@Entity
@Table(name = "sesion_caja")
@Getter
@Setter
public class SesionCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "consecutivo", nullable = false)
    private String consecutivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_apertura_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuarioApertura;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_cierre_id", columnDefinition = "INTEGER")
    private Usuario usuarioCierre;

    @Column(name = "fecha_apertura", nullable = false)
    private LocalDateTime fechaApertura;

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    /**
     * VESTIGIO DELIBERADO, siempre 0. El concepto de base inicial desapareció: el
     * arqueo es solo el dinero que entró y salió durante la sesión. La columna sigue
     * en la tabla (NOT NULL) para no reconstruir {@code sesion_caja} y su índice
     * parcial de sesión única abierta, y sin setter para que nadie la escriba: el
     * único valor posible es este 0. Las sesiones cerradas antes del cambio conservan
     * la base con que se abrieron, y su {@code efectivo_esperado} congelado la incluye.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "base_inicial", nullable = false, columnDefinition = "INTEGER")
    private long baseInicial = 0;

    @Column(name = "efectivo_esperado", columnDefinition = "INTEGER")
    private Long efectivoEsperado;

    @Column(name = "efectivo_contado", columnDefinition = "INTEGER")
    private Long efectivoContado;

    @Column(name = "diferencia", columnDefinition = "INTEGER")
    private Long diferencia;

    @Column(name = "monto_retirado", columnDefinition = "INTEGER")
    private Long montoRetirado;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoSesionCaja estado;

    @Column(name = "observaciones")
    private String observaciones;
}
