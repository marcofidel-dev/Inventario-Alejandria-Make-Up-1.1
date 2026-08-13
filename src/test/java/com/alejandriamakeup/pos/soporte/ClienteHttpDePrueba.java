package com.alejandriamakeup.pos.soporte;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Cliente HTTP para los tests de la API, con cookies.
 *
 * <p>La autenticación de esta app es una sesión HTTP, así que el cliente tiene que
 * conservar el {@code JSESSIONID} entre llamadas: sin eso no se puede probar nada
 * que dependa de estar autenticado. Se usa el {@link HttpClient} del JDK con un
 * {@link CookieManager}, que lo hace solo.
 *
 * <p>Cada instancia es una "persona" distinta frente al servidor: dos instancias
 * son dos navegadores, lo que permite probar en el mismo test qué ve la DUENA y qué
 * ve la EMPLEADA.
 */
public class ClienteHttpDePrueba {

    /** Estado, cuerpo y cabeceras de una respuesta, ya leídos. */
    public record Respuesta(int estado, String cuerpo, HttpHeaders cabeceras) {

        /** Una cabecera, o vacío si no vino. Útil para {@code Retry-After}. */
        public Optional<String> cabecera(String nombre) {
            return cabeceras.firstValue(nombre);
        }
    }

    private final HttpClient cliente;
    private final String base;

    public ClienteHttpDePrueba(int puerto) {
        this.base = "http://localhost:" + puerto;
        this.cliente = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Respuesta get(String ruta) {
        return enviar(peticion(ruta).GET().build());
    }

    public Respuesta post(String ruta, String json) {
        return enviar(peticion(ruta)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    public Respuesta post(String ruta) {
        return enviar(peticion(ruta).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    public Respuesta put(String ruta, String json) {
        return enviar(peticion(ruta)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create(base + ruta)).timeout(Duration.ofSeconds(20));
    }

    private Respuesta enviar(HttpRequest peticion) {
        try {
            HttpResponse<String> respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
            return new Respuesta(respuesta.statusCode(), respuesta.body(), respuesta.headers());
        } catch (IOException e) {
            throw new IllegalStateException("Falló la petición " + peticion.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Petición interrumpida " + peticion.uri(), e);
        }
    }
}
