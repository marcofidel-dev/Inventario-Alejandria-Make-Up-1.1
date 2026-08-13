package com.alejandriamakeup.pos.compras;

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
import lombok.Getter;
import lombok.Setter;

/**
 * Una compra a proveedor. En BORRADOR no ha tocado el inventario; al pasar a
 * RECIBIDA genera los movimientos de entrada.
 *
 * <p>El {@code consecutivo} sale de la tabla {@code consecutivo}, nunca de un
 * AUTOINCREMENT. Se asigna al crear el borrador, así que un borrador descartado
 * consume su número: el consecutivo no tiene huecos, tiene una compra que terminó
 * en DESCARTADA y se puede mirar.
 *
 * <p>Las tres columnas de baja —{@code fechaBaja}, {@code motivoBaja} y
 * {@code usuarioBaja}— van juntas o no va ninguna, y solo en los estados
 * terminales. No es una convención: lo impone un CHECK de la V5, porque una
 * anulación sin autor ni fecha es una pregunta que nadie va a poder responder seis
 * meses después.
 */
@Entity
@Table(name = "compra")
@Getter
@Setter
public class Compra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "consecutivo", nullable = false)
    private String consecutivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proveedor_id", nullable = false, columnDefinition = "INTEGER")
    private Proveedor proveedor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuario;

    @Column(name = "numero_factura")
    private String numeroFactura;

    @Column(name = "total", nullable = false, columnDefinition = "INTEGER")
    private long total;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoCompra estado;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "fecha_recepcion")
    private LocalDateTime fechaRecepcion;

    @Column(name = "notas")
    private String notas;

    @Column(name = "fecha_baja")
    private LocalDateTime fechaBaja;

    @Column(name = "motivo_baja")
    private String motivoBaja;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_baja_id", columnDefinition = "INTEGER")
    private Usuario usuarioBaja;

    /**
     * Deja la compra en un estado terminal con su rastro completo.
     *
     * <p>Un solo método para descartar y anular porque el CHECK de la V5 exige que
     * las tres columnas se escriban juntas: repartir la escritura entre dos sitios
     * es la forma de que algún día una de ellas se olvide.
     */
    public void darDeBaja(EstadoCompra estadoTerminal, String motivo, Usuario usuario,
                          LocalDateTime cuando) {
        this.estado = estadoTerminal;
        this.motivoBaja = motivo;
        this.usuarioBaja = usuario;
        this.fechaBaja = cuando;
    }
}
