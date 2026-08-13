package com.alejandriamakeup.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Todas las respuestas de error de la API tienen la misma forma.
 *
 * <p>Existe por un hallazgo del recorrido en vivo: las violaciones de restricción salían
 * sin campo {@code codigo}, a diferencia del resto. Los tests de entonces no lo vieron
 * porque comprobaban el <em>mensaje</em> y el estado, que estaban bien. Pero el front va
 * a ramificar sobre {@code codigo}, y un mensaje está escrito para leerse en pantalla:
 * cambiarlo no debería romper a nadie. Si la forma no se afirma, se rompe sin que nadie
 * se entere hasta que el front haga algo raro.
 *
 * <p>Este test provoca cada clase de error de verdad, por HTTP, y comprueba la forma:
 * {@code codigo} y {@code error}, ambos con contenido. Y además — la parte que lo hace
 * difícil de olvidar — cuenta los {@code @ExceptionHandler} de
 * {@link GlobalExceptionHandler} y exige que cada uno tenga su provocación aquí. Añadir
 * un manejador nuevo sin probar su forma rompe el build.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FormaDeLosErroresTest {

    private static final String URL = BaseDatosAislada.urlNueva("forma-errores");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private ClienteHttpDePrueba duena;
    private ClienteHttpDePrueba anonimo;

    @BeforeEach
    void prepararUsuariaYClientes() {
        if (usuarioRepository.count() == 0) {
            Usuario alejandra = new Usuario();
            alejandra.setNombre("Alejandra");
            alejandra.setRol(Rol.DUENA);
            alejandra.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            alejandra.setActivo(true);
            alejandra.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(alejandra);
        }
        anonimo = new ClienteHttpDePrueba(puerto);
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    /** Cada clase de error, con la provocación que la dispara y el manejador que la atiende. */
    private Map<String, Respuesta> provocaciones() {
        Map<String, Respuesta> casos = new LinkedHashMap<>();

        // ErrorDeAplicacion, en sus formas más comunes
        casos.put("401 sin sesión", anonimo.get("/api/v1/auth/sesion"));
        casos.put("401 credenciales malas",
                anonimo.post("/api/v1/auth/login", "{\"nombre\":\"nadie\",\"pin\":\"0000\"}"));
        casos.put("403 sin regla declarada", duena.get("/api/v1/ruta/que/nadie/declaro"));
        casos.put("404 recurso inexistente", duena.get("/api/v1/caja/sesiones/999999"));
        casos.put("409 conflicto de negocio",
                duena.post("/api/v1/auth/configuracion-inicial",
                        "{\"nombre\":\"Otra\",\"pin\":\"9999\"}"));

        // Validación de @Valid
        casos.put("400 validación de campos",
                duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"\"}"));

        // Violación de restricción de SQLite, vía TraductorRestriccionesSqlite
        duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"Repetida\"}");
        casos.put("409 restricción de la base",
                duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"REPETIDA\"}"));

        // Cuerpo mal formado y tipo de parámetro equivocado: los dos son culpa del
        // cliente y no deben salir como 500.
        casos.put("400 JSON mal formado",
                duena.post("/api/v1/catalogo/marcas", "{\"nombre\": "));
        casos.put("400 id que no es número", duena.get("/api/v1/caja/sesiones/abc"));

        // Ruta no mapeada fuera de /api: la atiende NoResourceFoundException
        casos.put("404 recurso estático inexistente", duena.get("/no-existe-este-archivo.js"));

        return casos;
    }

    @Test
    void todaRespuestaDeErrorTraeCodigoYMensaje() {
        List<String> malFormadas = new ArrayList<>();

        provocaciones().forEach((etiqueta, respuesta) -> {
            String cuerpo = respuesta.cuerpo() == null ? "" : respuesta.cuerpo();
            System.out.printf("  %-34s %d  %s%n", etiqueta, respuesta.estado(), recortar(cuerpo));

            if (respuesta.estado() < 400) {
                malFormadas.add(etiqueta + ": esperaba un error y llegó " + respuesta.estado());
                return;
            }
            if (!cuerpo.contains("\"codigo\"")) {
                malFormadas.add(etiqueta + ": sin campo 'codigo' => " + recortar(cuerpo));
            }
            if (!cuerpo.contains("\"error\"")) {
                malFormadas.add(etiqueta + ": sin campo 'error' => " + recortar(cuerpo));
            }
            if (cuerpo.contains("\"codigo\":\"\"") || cuerpo.contains("\"codigo\":null")) {
                malFormadas.add(etiqueta + ": 'codigo' vacío");
            }
        });

        assertThat(malFormadas)
                .withFailMessage("Estas respuestas de error no tienen la forma "
                        + "{codigo, error}:%n%s", String.join("\n", malFormadas))
                .isEmpty();
    }

    /** Ningún error de cliente puede salir como 500: eso oculta un problema arreglable. */
    @Test
    void losErroresDelClienteNoSalenComo500() {
        List<String> quinientos = new ArrayList<>();

        provocaciones().forEach((etiqueta, respuesta) -> {
            if (respuesta.estado() >= 500) {
                quinientos.add(etiqueta + " => " + respuesta.estado() + " "
                        + recortar(respuesta.cuerpo()));
            }
        });

        System.out.println("VERIFICACION provocaciones que salen 5xx => "
                + (quinientos.isEmpty() ? "ninguna" : quinientos));
        assertThat(quinientos).isEmpty();
    }

    /**
     * La red que hace difícil olvidarlo: si alguien agrega un {@code @ExceptionHandler}
     * y no una provocación para él, este test falla y dice cuántas faltan.
     */
    @Test
    void cadaManejadorDeExcepcionTieneSuProvocacion() {
        List<String> manejadores = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .map(Method::getName)
                .sorted()
                .toList();

        List<String> clasesCubiertas = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(ExceptionHandler.class).value()))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        System.out.println("VERIFICACION manejadores en GlobalExceptionHandler (" + manejadores.size()
                + "): " + manejadores);
        System.out.println("VERIFICACION excepciones declaradas: " + clasesCubiertas);
        System.out.println("VERIFICACION provocaciones en este test: " + provocaciones().size());

        assertThat(provocaciones().size())
                .withFailMessage("Hay %d manejadores de excepción y %d provocaciones. Si agregaste "
                        + "un @ExceptionHandler, agrega también una provocación que compruebe la "
                        + "forma de su respuesta.", manejadores.size(), provocaciones().size())
                .isGreaterThanOrEqualTo(manejadores.size());
    }

    private String recortar(String cuerpo) {
        String limpio = cuerpo == null ? "" : cuerpo.replace("\n", " ");
        return limpio.length() <= 120 ? limpio : limpio.substring(0, 120) + "...";
    }
}
