package com.alejandriamakeup.pos.caja;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;

import com.alejandriamakeup.pos.usuarios.Usuario;

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
 * Lo que alguien anotó sobre una sesión de caja.
 *
 * <p>Append-only, igual que los dos ledgers: nunca UPDATE, nunca DELETE, sin
 * setters y con {@link Immutable}. Existe para que una diferencia de arqueo se
 * pueda explicar <strong>después</strong> de conocerla, que es el único momento en
 * que hay algo que explicar — y sin tocar la fila de {@link SesionCaja}, que queda
 * inmutable al pie de la letra.
 *
 * <p>La nota lleva su propio {@code usuario}: quien explica un descuadre no tiene
 * por qué ser quien cerró la caja, y que la dueña anote sobre el turno de la
 * empleada es información útil precisamente porque queda claro quién escribió qué.
 */
@Entity
@Table(name = "nota_sesion_caja")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotaSesionCaja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", columnDefinition = "INTEGER")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_id", nullable = false, columnDefinition = "INTEGER")
    private SesionCaja sesion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, columnDefinition = "INTEGER")
    private Usuario usuario;

    @Column(name = "fecha", nullable = false)
    private LocalDateTime fecha;

    @Column(name = "texto", nullable = false)
    private String texto;
}
