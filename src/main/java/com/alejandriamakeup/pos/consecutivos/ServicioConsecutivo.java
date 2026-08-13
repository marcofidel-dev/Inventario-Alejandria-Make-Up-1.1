package com.alejandriamakeup.pos.consecutivos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Entrega el siguiente consecutivo de una serie, incrementando el contador
 * dentro de la transacción de quien llama.
 *
 * <p>Existe porque el AUTOINCREMENT de SQLite salta números cuando una
 * transacción hace rollback, y un recibo con un hueco en la numeración es un
 * problema contable, no cosmético.
 *
 * <p>Dos decisiones que sostienen la garantía:
 *
 * <ul>
 *   <li>{@link Propagation#MANDATORY}: no se puede llamar fuera de una
 *       transacción. Si el documento hace rollback, el consecutivo vuelve atrás
 *       con él y no queda quemado. Llamarlo sin transacción no es un bug
 *       silencioso, es una excepción.
 *   <li>Una sola sentencia {@code UPDATE ... RETURNING}: leer y luego escribir
 *       dejaría una ventana entre ambas. Aquí el incremento y la lectura del
 *       valor resultante son la misma operación.
 * </ul>
 */
@Service
public class ServicioConsecutivo {

    private static final String SQL_INCREMENTAR = """
            UPDATE consecutivo
               SET ultimo_numero = ultimo_numero + 1
             WHERE tipo = :tipo
            RETURNING prefijo, ultimo_numero
            """;

    private static final String FORMATO_NUMERO = "%06d";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Incrementa el contador de {@code tipo} y devuelve el consecutivo formateado,
     * por ejemplo {@code V-000001}.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si se llama fuera de una transacción
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String siguiente(TipoConsecutivo tipo) {
        Object[] fila = (Object[]) entityManager.createNativeQuery(SQL_INCREMENTAR)
                .setParameter("tipo", tipo.name())
                .getSingleResult();

        String prefijo = (String) fila[0];
        long numero = ((Number) fila[1]).longValue();
        return prefijo + "-" + String.format(FORMATO_NUMERO, numero);
    }
}
