package com.alejandriamakeup.pos.ventas;

import org.hibernate.annotations.Immutable;

import com.alejandriamakeup.pos.catalogo.Variante;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Una línea de venta, con precio, costo y descripción <strong>congelados</strong>
 * al momento de venderse. Sin ese congelado, cambiar un precio en el catálogo
 * corrompería retroactivamente todas las métricas históricas de margen.
 *
 * <p>Por eso es inmutable: sin setters y con {@link Immutable}, de modo que
 * Hibernate no pueda emitir un UPDATE sobre esta tabla ni por accidente.
 *
 * <p>{@code descuentoProrrateado} es el descuento de cabecera repartido
 * proporcionalmente y congelado aquí, para que el margen por producto no
 * dependa de recalcularlo.
 */
@Entity
@Table(name = "venta_item")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VentaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venta_id", nullable = false, columnDefinition = "INTEGER")
    private Venta venta;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variante_id", nullable = false, columnDefinition = "INTEGER")
    private Variante variante;

    @Column(name = "cantidad", nullable = false, columnDefinition = "INTEGER")
    private int cantidad;

    @Column(name = "precio_unitario_congelado", nullable = false, columnDefinition = "INTEGER")
    private long precioUnitarioCongelado;

    @Column(name = "costo_unitario_congelado", nullable = false, columnDefinition = "INTEGER")
    private long costoUnitarioCongelado;

    @Column(name = "descripcion_congelada", nullable = false)
    private String descripcionCongelada;

    @Column(name = "descuento_prorrateado", nullable = false, columnDefinition = "INTEGER")
    private long descuentoProrrateado;

    @Column(name = "subtotal", nullable = false, columnDefinition = "INTEGER")
    private long subtotal;
}
