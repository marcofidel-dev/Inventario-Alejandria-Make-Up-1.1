package com.alejandriamakeup.pos.configuracion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Un ajuste del sistema, en forma clave/valor.
 *
 * <p>La tabla existe desde V1 —antes que cualquier tabla de dominio— y estuvo sin
 * usar hasta la Fase 10, cuando el encabezado del recibo necesitó saber cómo se llama
 * la tienda.
 *
 * <p>Clave/valor en texto y no una tabla de una fila con cinco columnas: lo que se
 * guarda aquí son datos de presentación que crecen de uno en uno (mañana, un horario
 * o un mensaje de garantía), y agregarlos no puede costar una migración en SQLite,
 * donde alterar una columna obliga a reconstruir la tabla. A cambio se pierde el
 * tipado, que para cinco cadenas que se imprimen tal cual no es pérdida.
 */
@Entity
@Table(name = "configuracion")
@Getter
@Setter
public class Configuracion {

    @Id
    @Column(name = "clave", nullable = false)
    private String clave;

    @Column(name = "valor")
    private String valor;
}
