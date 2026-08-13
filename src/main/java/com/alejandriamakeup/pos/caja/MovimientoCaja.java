package com.alejandriamakeup.pos.caja;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.ventas.Venta;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * El ledger del cajón. Append-only igual que el de inventario: nunca UPDATE,
 * nunca DELETE, sin setters y con {@link Immutable}.
 *
 * <p>Solo el efectivo llega aquí. Las ventas por tarjeta, Nequi, Daviplata o
 * transferencia <strong>no</strong> generan movimiento de caja: se concilian
 * aparte contra el extracto.
 *
 * <p>{@code monto} va con signo. El esquema solo prohíbe el cero.
 */
@Entity
@Table(name = "movimiento_caja")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MovimientoCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_id", nullable = false, columnDefinition = "INTEGER")
    private SesionCaja sesion;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoMovimientoCaja tipo;

    @Column(name = "monto", nullable = false, columnDefinition = "INTEGER")
    private long monto;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venta_id", columnDefinition = "INTEGER")
    private Venta venta;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuario;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "concepto")
    private String concepto;
}
