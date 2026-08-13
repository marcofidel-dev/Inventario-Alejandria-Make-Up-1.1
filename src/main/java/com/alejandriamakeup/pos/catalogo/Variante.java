package com.alejandriamakeup.pos.catalogo;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * La variante es la unidad real de todo el sistema: lo que se vende, lo que se
 * compra y aquello de lo que hay stock.
 *
 * <p>{@code precioVenta} y {@code costoPromedio} son {@code long} en pesos
 * enteros. El precio de aquí es el precio <em>actual</em>: la venta congela el
 * suyo en {@code VentaItem}, así que cambiarlo no reescribe la historia.
 *
 * <p>No hay campo de stock. El stock es
 * {@code SUM(cantidad)} sobre {@code movimiento_inventario}.
 */
@Entity
@Table(name = "variante")
@Getter
@Setter
public class Variante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "producto_id", nullable = false, columnDefinition = "INTEGER")
    private Producto producto;

    @Column(name = "tono")
    private String tono;

    @Column(name = "tamano")
    private String tamano;

    @Column(name = "codigo_barras")
    private String codigoBarras;

    @Column(name = "precio_venta", nullable = false, columnDefinition = "INTEGER")
    private long precioVenta;

    @Column(name = "costo_promedio", nullable = false, columnDefinition = "INTEGER")
    private long costoPromedio;

    @Column(name = "stock_minimo", nullable = false, columnDefinition = "INTEGER")
    private int stockMinimo;

    /**
     * Fecha sin hora: se guarda como {@code 'YYYY-MM-DD'}. Al filtrar en SQL se
     * compara contra {@code date('now')}, nunca contra {@code datetime('now')} —
     * ver {@code LocalDateIsoConverter}.
     */
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    /** Period After Opening: meses de vida útil una vez abierto el producto. */
    @Column(name = "pao_meses", columnDefinition = "INTEGER")
    private Integer paoMeses;

    @Column(name = "activo", nullable = false, columnDefinition = "INTEGER")
    private boolean activo = true;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;
}
