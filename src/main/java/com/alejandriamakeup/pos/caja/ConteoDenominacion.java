package com.alejandriamakeup.pos.caja;

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
 * Cuántos billetes o monedas de cada denominación se contaron al cerrar. Tabla
 * aparte y no diez columnas, para que agregar una denominación no sea una
 * migración.
 *
 * <p>{@code denominacion} es un monto en pesos ({@code long}); {@code cantidad}
 * es un conteo de piezas.
 */
@Entity
@Table(name = "conteo_denominacion")
@Getter
@Setter
public class ConteoDenominacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_id", nullable = false, columnDefinition = "INTEGER")
    private SesionCaja sesion;

    @Column(name = "denominacion", nullable = false, columnDefinition = "INTEGER")
    private long denominacion;

    @Column(name = "cantidad", nullable = false, columnDefinition = "INTEGER")
    private int cantidad;
}
