package com.alejandriamakeup.pos.autenticacion;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Un intento de inicio de sesión. Append-only, como los otros ledgers: sin
 * setters y con {@link Immutable}.
 *
 * <p>{@code nombre} es texto libre y <strong>no</strong> una FK a
 * {@code usuario}: un intento fallido puede traer un nombre que no existe, y
 * registrar también esos es justamente el punto — si alguien está probando
 * nombres, queda el rastro.
 */
@Entity
@Table(name = "intento_login")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IntentoLogin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "exito", nullable = false, columnDefinition = "INTEGER")
    private boolean exito;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;
}
