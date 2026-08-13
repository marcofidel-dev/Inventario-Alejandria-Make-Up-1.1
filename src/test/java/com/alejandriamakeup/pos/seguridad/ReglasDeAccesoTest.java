package com.alejandriamakeup.pos.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.alejandriamakeup.pos.PosApplication;

/**
 * El test que hace imposible olvidar una regla de acceso.
 *
 * <p>Enumera todos los endpoints que Spring tiene mapeados y exige que
 * {@link ReglasDeAcceso} resuelva una regla para cada uno. Un controlador nuevo sin
 * regla declarada rompe el build, que es cuando duele barato — en vez de salir a la
 * tienda abierto, o de aparecer como un 403 inexplicable el día que alguien lo use.
 *
 * <p>El complemento en caliente está en {@code AutorizacionHttpTest}: una ruta sin
 * regla se niega en ejecución. Este test evita que eso pase; ese otro garantiza que,
 * si pasa, el default sea negar.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class ReglasDeAccesoTest {

    @Autowired
    private ReglasDeAcceso reglas;

    @Autowired
    private RequestMappingHandlerMapping mapeos;

    @Test
    void todoEndpointDeLaApiTieneReglaDeclarada() {
        List<String> sinRegla = new ArrayList<>();
        List<String> conRegla = new ArrayList<>();

        mapeos.getHandlerMethods().forEach((info, handler) -> {
            for (Ruta ruta : rutasDe(info, handler)) {
                if (!ruta.esDeLaApi()) {
                    continue;
                }
                if (reglas.para(ruta.metodo(), ruta.concreta()).isPresent()) {
                    conRegla.add(ruta.describir());
                } else {
                    sinRegla.add(ruta.describir());
                }
            }
        });

        System.out.println("VERIFICACION endpoints de la API con regla (" + conRegla.size() + "):");
        conRegla.stream().sorted().forEach(r -> System.out.println("    " + r));

        assertThat(sinRegla)
                .withFailMessage("Estos endpoints no tienen regla en ReglasDeAcceso y por lo tanto "
                        + "quedarían negados en ejecución: %s", sinRegla)
                .isEmpty();
        assertThat(conRegla).isNotEmpty();
    }

    /**
     * Un patrón con variable como {@code /sesiones/{id}} se convierte en una ruta
     * concreta para poder resolverlo igual que lo haría una petición real.
     */
    private record Ruta(HttpMethod metodo, String patron) {

        String concreta() {
            return patron.replaceAll("\\{[^/}]+}", "1");
        }

        boolean esDeLaApi() {
            return patron.startsWith("/api/");
        }

        String describir() {
            return metodo + " " + patron;
        }
    }

    private List<Ruta> rutasDe(RequestMappingInfo info, HandlerMethod handler) {
        List<Ruta> rutas = new ArrayList<>();
        var patrones = info.getPathPatternsCondition();
        if (patrones == null) {
            return rutas;
        }

        for (var patron : patrones.getPatterns()) {
            var metodos = info.getMethodsCondition().getMethods();
            if (metodos.isEmpty()) {
                // Un mapeo sin método explícito responde a todos: se audita como GET,
                // que es el caso peligroso (lectura de datos).
                rutas.add(new Ruta(HttpMethod.GET, patron.getPatternString()));
                continue;
            }
            for (var metodo : metodos) {
                rutas.add(new Ruta(HttpMethod.valueOf(metodo.name()), patron.getPatternString()));
            }
        }
        return rutas;
    }
}
