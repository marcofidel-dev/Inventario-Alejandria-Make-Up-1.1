package com.alejandriamakeup.pos.consecutivos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * El contador de cada serie de documentos. Su clave primaria es el propio
 * {@code tipo}, así que no lleva columna {@code id} ni {@code @GeneratedValue}.
 *
 * <p>Existe porque el AUTOINCREMENT de SQLite salta números si una transacción
 * hace rollback, y un recibo con un hueco en la numeración es un problema
 * contable.
 *
 * <p>Las filas las inserta la migración V2; el código solo incrementa. Ver
 * {@link ServicioConsecutivo}.
 */
@Entity
@Table(name = "consecutivo")
@Getter
@Setter
public class Consecutivo {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoConsecutivo tipo;

    @Column(name = "prefijo", nullable = false)
    private String prefijo;

    @Column(name = "ultimo_numero", nullable = false, columnDefinition = "INTEGER")
    private long ultimoNumero;
}
