package com.alejandriamakeup.pos.ventas;

import java.time.LocalDateTime;

import com.alejandriamakeup.pos.caja.SesionCaja;
import com.alejandriamakeup.pos.clientes.Cliente;
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
 * Una venta. Ninguna existe fuera de una sesión de caja abierta, y su
 * {@code consecutivo} sale de la tabla {@code consecutivo}.
 *
 * <p>Mutable a propósito, pero solo por la anulación: una venta anulada conserva
 * sus items y sus movimientos, y la corrección se hace con movimientos nuevos de
 * tipo ANULACION. Nunca se borra ni se reescribe una venta.
 *
 * <p>{@code rutaRecibo} apunta al PDF, que se genera <em>después</em> del commit.
 * La venta es la verdad; el PDF es derivado y puede regenerarse.
 */
@Entity
@Table(name = "venta")
@Getter
@Setter
public class Venta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "uuid", nullable = false)
    private String uuid;

    @Column(name = "consecutivo", nullable = false)
    private String consecutivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_caja_id", nullable = false, columnDefinition = "INTEGER")
    private SesionCaja sesionCaja;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id", columnDefinition = "INTEGER")
    private Cliente cliente;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "subtotal", nullable = false, columnDefinition = "INTEGER")
    private long subtotal;

    @Column(name = "descuento", nullable = false, columnDefinition = "INTEGER")
    private long descuento;

    @Column(name = "total", nullable = false, columnDefinition = "INTEGER")
    private long total;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago", nullable = false)
    private MetodoPago metodoPago;

    @Column(name = "efectivo_recibido", columnDefinition = "INTEGER")
    private Long efectivoRecibido;

    @Column(name = "cambio", columnDefinition = "INTEGER")
    private Long cambio;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoVenta estado;

    @Column(name = "fecha_anulacion")
    private LocalDateTime fechaAnulacion;

    @Column(name = "motivo_anulacion")
    private String motivoAnulacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_anulacion_id", columnDefinition = "INTEGER")
    private Usuario usuarioAnulacion;

    @Column(name = "ruta_recibo")
    private String rutaRecibo;
}
