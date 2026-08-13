package com.alejandriamakeup.pos.compras;

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
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "compra_item")
@Getter
@Setter
public class CompraItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compra_id", nullable = false, columnDefinition = "INTEGER")
    private Compra compra;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variante_id", nullable = false, columnDefinition = "INTEGER")
    private Variante variante;

    @Column(name = "cantidad", nullable = false, columnDefinition = "INTEGER")
    private int cantidad;

    @Column(name = "costo_unitario", nullable = false, columnDefinition = "INTEGER")
    private long costoUnitario;

    @Column(name = "subtotal", nullable = false, columnDefinition = "INTEGER")
    private long subtotal;
}
