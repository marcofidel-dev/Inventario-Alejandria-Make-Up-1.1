package com.alejandriamakeup.pos.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resuelve las rutas de datos de la aplicación. Nunca relativas al JAR ni
 * dentro de la carpeta de instalación: la primera actualización borraría el
 * inventario del negocio.
 */
public final class AppPaths {

    private static final String NOMBRE_CARPETA = "AlejandriaMakeUp";

    private AppPaths() {
    }

    public record Rutas(Path directorioDatos, Path directorioRecibos, Path directorioBackups) {

        public Path archivoBaseDatos() {
            return directorioDatos.resolve("data.db");
        }

        public void crearDirectorios() {
            crear(directorioDatos);
            crear(directorioRecibos);
            crear(directorioBackups);
        }

        private static void crear(Path directorio) {
            try {
                Files.createDirectories(directorio);
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo crear el directorio " + directorio, e);
            }
        }
    }

    public static Rutas resolver(List<String> perfilesActivos) {
        Path base = raiz(perfilesActivos);
        return new Rutas(base.resolve("data"), base.resolve("recibos"), base.resolve("backups"));
    }

    private static Path raiz(List<String> perfilesActivos) {
        if (perfilesActivos.contains("test")) {
            return Path.of(System.getProperty("java.io.tmpdir"), NOMBRE_CARPETA + "-test");
        }

        Path appData = rutaAppData();
        Path raizProduccion = appData.resolve(NOMBRE_CARPETA);
        return perfilesActivos.contains("dev") ? raizProduccion.resolve("dev") : raizProduccion;
    }

    private static Path rutaAppData() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData);
        }
        // Fallback para entornos no Windows (desarrollo/CI).
        return Path.of(System.getProperty("user.home"), ".config");
    }
}
