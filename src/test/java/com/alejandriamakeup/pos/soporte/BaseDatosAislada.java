package com.alejandriamakeup.pos.soporte;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Una base de datos SQLite recién migrada, propia de una clase de test.
 *
 * <p>Hace falta porque varias reglas de esta fase son sobre estado
 * <strong>global</strong>: solo puede haber una sesión de caja abierta en todo el
 * sistema, y la configuración inicial solo aplica cuando no hay ningún usuario.
 * Contra la base compartida, un test que deja una sesión abierta rompe al
 * siguiente, y una corrida anterior rompe a la de mañana — el mismo problema de
 * residuo que ya apareció en la auditoría de la fase pasada.
 *
 * <p>Se usa con {@code @DynamicPropertySource} sobreescribiendo
 * {@code spring.datasource.url}. Flyway migra el archivo nuevo desde cero, así que
 * el test arranca en un mundo conocido y vacío.
 */
public final class BaseDatosAislada {

    private BaseDatosAislada() {
    }

    /** Una URL JDBC hacia un archivo nuevo, con los mismos parámetros que producción. */
    public static String urlNueva(String etiqueta) {
        Path archivo = Path.of(System.getProperty("java.io.tmpdir"),
                "AlejandriaMakeUp-test-aislado",
                etiqueta + "-" + UUID.randomUUID() + ".db");
        archivo.getParent().toFile().mkdirs();
        return "jdbc:sqlite:" + archivo + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000";
    }
}
