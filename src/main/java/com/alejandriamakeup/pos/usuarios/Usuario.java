package com.alejandriamakeup.pos.usuarios;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Un usuario del sistema. El PIN se guarda hasheado con BCrypt, nunca en claro.
 *
 * <p>Sobre los {@code columnDefinition = "INTEGER"}: el esquema usa {@code INTEGER}
 * para los enteros, pero Hibernate emite {@code bigint} para un {@code Long} y
 * {@code boolean} para un {@code boolean}, y con {@code ddl-auto: validate}
 * ninguno de los dos calza contra {@code INTEGER} — ni por código JDBC ni por
 * nombre de tipo. Declarar el tipo explícitamente es lo que hace que el mapeo sea
 * exacto contra el esquema.
 */
@Entity
@Table(name = "usuario")
@Getter
@Setter
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false)
    private Rol rol;

    @Column(name = "activo", nullable = false, columnDefinition = "INTEGER")
    private boolean activo = true;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;
}
