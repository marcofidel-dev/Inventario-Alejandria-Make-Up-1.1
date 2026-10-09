package com.alejandriamakeup.pos.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sirve para saber que lo que responde en el puerto es esta app y no otro
 * programa: {@code nombre} y {@code version} vienen de {@code app.nombre} y
 * {@code app.version} en {@code application.yml}, esta última filtrada por
 * Maven desde {@code pom.xml} en el build.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final String nombre;
    private final String version;

    public HealthController(@Value("${app.nombre}") String nombre, @Value("${app.version}") String version) {
        this.nombre = nombre;
        this.version = version;
    }

    @GetMapping
    public Map<String, String> salud() {
        return Map.of("status", "UP", "nombre", nombre, "version", version);
    }
}
