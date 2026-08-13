package com.alejandriamakeup.pos.inventario;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.compras.Compra;
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
 * El ledger de inventario. <strong>El stock ES la suma de {@code cantidad} sobre
 * esta tabla</strong>, nunca un campo que se actualiza.
 *
 * <p>Append-only: nunca UPDATE, nunca DELETE. Un error se corrige con un
 * movimiento de ajuste, no editando el anterior. La clase no tiene setters y
 * lleva {@link Immutable} para que Hibernate no pueda emitir un UPDATE ni por
 * accidente.
 *
 * <p>{@code cantidad} va con signo: positiva en compras y anulaciones de venta,
 * negativa en ventas y mermas. El esquema solo prohíbe el cero.
 */
@Entity
@Table(name = "movimiento_inventario")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MovimientoInventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variante_id", nullable = false, columnDefinition = "INTEGER")
    private Variante variante;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoMovimientoInventario tipo;

    @Column(name = "cantidad", nullable = false, columnDefinition = "INTEGER")
    private int cantidad;

    @Column(name = "costo_unitario", nullable = false, columnDefinition = "INTEGER")
    private long costoUnitario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compra_id", columnDefinition = "INTEGER")
    private Compra compra;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venta_id", columnDefinition = "INTEGER")
    private Venta venta;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuario;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "motivo")
    private String motivo;
}
