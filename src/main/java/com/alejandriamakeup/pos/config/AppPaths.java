package com.alejandriamakeup.pos.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resuelve las rutas de datos de la aplicación. Nunca relativas al JAR ni
 * dentro de la carpeta de instalación: la primera actualización borraría el
 * inventario del negocio.
 */
public final class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);
    private static final String NOMBRE_CARPETA = "AlejandriaMakeUp";

    private AppPaths() {
    }

    /**
     * @param directorioRaiz la carpeta que contiene a las otras cuatro. Existe porque
     *        {@code venta.ruta_recibo} se guarda <strong>relativa</strong>
     *        —{@code recibos/2026/08/V-000123.pdf}— y hace falta un origen contra el
     *        que resolverla. Relativa y no absoluta para que mover la carpeta de datos
     *        de equipo, o de letra de unidad, no deje todos los recibos ilocalizables.
     */
    public record Rutas(Path directorioRaiz, Path directorioDatos, Path directorioRecibos,
                        Path directorioBackups, Path directorioLogs) {

        public Path archivoBaseDatos() {
            return directorioDatos.resolve("data.db");
        }

        public void crearDirectorios() {
            crear(directorioDatos);
            crear(directorioRecibos);
            crear(directorioBackups);
            // Placeholder: nada escribe aquí todavía, no hay logging a archivo.
            crear(directorioLogs);
        }

        private static void crear(Path directorio) {
            try {
                Files.createDirectories(directorio);
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo crear el directorio " + directorio, e);
            }
        }
    }

    /**
     * Las cinco rutas. {@code recibos} y {@code backups} cuelgan de la <em>misma</em>
     * raíz a propósito: la carpeta de datos es la que se sincroniza con la nube, y así
     * la sincronización que cubre los respaldos cubre también los comprobantes, que
     * hay que conservar cinco años.
     */
    public static Rutas resolver(List<String> perfilesActivos) {
        return resolver(perfilesActivos, System.getProperty("os.name", ""),
                System.getenv("APPDATA"), System.getProperty("user.home"));
    }

    /** Con el sistema, el APPDATA y el home inyectados: así se testean las tres ramas sin depender de la máquina. */
    static Rutas resolver(List<String> perfilesActivos, String osName, String appDataEnv, String home) {
        Path base = raiz(perfilesActivos, osName, appDataEnv, home);
        return new Rutas(base, base.resolve("data"), base.resolve("recibos"),
                base.resolve("backups"), base.resolve("logs"));
    }

    private static Path raiz(List<String> perfilesActivos, String osName, String appDataEnv, String home) {
        if (perfilesActivos.contains("test")) {
            return Path.of(System.getProperty("java.io.tmpdir"), NOMBRE_CARPETA + "-test");
        }

        Path raizProduccion = raizPorSistema(osName, appDataEnv, home);
        return perfilesActivos.contains("dev") ? raizProduccion.resolve("dev") : raizProduccion;
    }

    /**
     * Cada sistema guarda los datos de una app donde su convención manda: Windows en
     * {@code %APPDATA%}, macOS en {@code ~/Library/Application Support}, y cualquier
     * otro —Linux, en la práctica— en {@code ~/.config} (XDG).
     *
     * <p>Antes de esta versión, macOS caía sin querer en la rama de Linux —no había
     * rama propia— así que un equipo Mac que ya corrió la app puede tener datos en
     * {@code ~/.config/AlejandriaMakeUp}. Esa carpeta no se migra sola: mover el
     * inventario de un negocio sin que nadie lo decida es exactamente lo que este
     * código no debe hacer. Solo se avisa en el log con las dos rutas para que quien
     * instale sepa que hay un rastro viejo.
     */
    private static Path raizPorSistema(String osName, String appDataEnv, String home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return rutaAppData(appDataEnv, home).resolve(NOMBRE_CARPETA);
        }
        if (os.contains("mac")) {
            Path nueva = Path.of(home, "Library", "Application Support", NOMBRE_CARPETA);
            Path vieja = Path.of(home, ".config", NOMBRE_CARPETA);
            if (Files.exists(vieja)) {
                log.warn("Encontrados datos en {} (ruta de antes de separar macOS de Linux). "
                        + "No se migran automáticamente; la ruta activa ahora es {}.", vieja, nueva);
            }
            return nueva;
        }
        // Linux y cualquier otro: convención XDG.
        return Path.of(home, ".config", NOMBRE_CARPETA);
    }

    private static Path rutaAppData(String appDataEnv, String home) {
        if (appDataEnv != null && !appDataEnv.isBlank()) {
            return Path.of(appDataEnv);
        }
        // Fallback para Windows sin APPDATA seteado (infrecuente: entornos de prueba).
        return Path.of(home, ".config");
    }
}
