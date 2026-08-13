package com.alejandriamakeup.pos.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Se ejecuta antes de que se construya el DataSource, para que
 * {@code ${APP_DB_PATH}} exista cuando Hikari resuelve la URL JDBC.
 */
public class AppPathsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> perfiles = Arrays.asList(environment.getActiveProfiles());
        if (perfiles.isEmpty()) {
            perfiles = Arrays.asList(environment.getDefaultProfiles());
        }

        AppPaths.Rutas rutas = AppPaths.resolver(perfiles);
        rutas.crearDirectorios();

        Map<String, Object> propiedades = new LinkedHashMap<>();
        propiedades.put("APP_DB_PATH", rutas.archivoBaseDatos().toString());
        propiedades.put("app.paths.data", rutas.directorioDatos().toString());
        propiedades.put("app.paths.recibos", rutas.directorioRecibos().toString());
        propiedades.put("app.paths.backups", rutas.directorioBackups().toString());

        environment.getPropertySources().addFirst(new MapPropertySource("appPaths", propiedades));
    }
}
