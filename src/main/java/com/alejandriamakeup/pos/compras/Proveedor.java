package com.alejandriamakeup.pos.compras;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "proveedor")
@Getter
@Setter
public class Proveedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "nit")
    private String nit;

    @Column(name = "telefono")
    private String telefono;

    @Column(name = "contacto")
    private String contacto;

    @Column(name = "notas")
    private String notas;

    @Column(name = "activo", nullable = false, columnDefinition = "INTEGER")
    private boolean activo = true;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;
}
