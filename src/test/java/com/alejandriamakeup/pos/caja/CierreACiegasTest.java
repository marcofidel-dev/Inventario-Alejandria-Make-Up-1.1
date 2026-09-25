package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * La regla más fácil de romper sin darse cuenta, y la razón de ser de toda la
 * funcionalidad de arqueo: <strong>ningún endpoint puede revelar el efectivo
 * esperado antes del conteo</strong>.
 *
 * <p>Este test no comprueba un endpoint concreto: <strong>barre todos</strong>. Toma
 * los GET que Spring tiene mapeados bajo {@code /api/v1/**}, los llama con una
 * sesión abierta de números conocidos, y exige que ningún cuerpo contenga el
 * esperado ni las claves que lo nombran.
 *
 * <p>Se hizo así porque la fuga no va a llegar por el endpoint que uno está
 * mirando. Va a llegar por uno nuevo de la Fase 3 — un resumen del día, un panel de
 * métricas, un "estado de la caja" para el encabezado — escrito por alguien que no
 * tenía esta regla en la cabeza. Un barrido automático cubre también los endpoints
 * que todavía no existen.
 *
 * <p>Los números están elegidos para no confundirse con nada: dos ingresos, 41.000
 * y 22.000, dan un esperado de 63.000. Son dos a propósito: el esperado es ahora solo
 * la suma de los movimientos, y con uno solo coincidiría con el monto tecleado — que la
 * lista de movimientos sí muestra por decisión explícita — y el barrido no distinguiría
 * una fuga del esperado de un movimiento visible. La suma es una cifra que nadie tecleó.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class CierreACiegasTest {

    private static final String URL = BaseDatosAislada.urlNueva("cierre-a-ciegas");

    private static final long INGRESO = 41_000;
    private static final long OTRO_INGRESO = 22_000;
    private static final long ESPERADO = INGRESO + OTRO_INGRESO;

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private RequestMappingHandlerMapping mapeos;

    private ClienteHttpDePrueba duena;
    private Long idSesion;

    @BeforeEach
    void abrirSesionConNumerosConocidos() {
        if (usuarioRepository.count() == 0) {
            Usuario alejandra = new Usuario();
            alejandra.setNombre("Alejandra");
            alejandra.setRol(Rol.DUENA);
            alejandra.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            alejandra.setActivo(true);
            alejandra.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(alejandra);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");

        Respuesta actual = duena.get("/api/v1/caja/sesiones/actual");
        if (actual.estado() == 200) {
            idSesion = extraerId(actual.cuerpo());
            return;
        }

        Respuesta apertura = duena.post("/api/v1/caja/sesiones", "{}");
        assertThat(apertura.estado()).isEqualTo(201);
        idSesion = extraerId(apertura.cuerpo());

        duena.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"INGRESO\",\"monto\":" + INGRESO + ",\"concepto\":\"ingreso de prueba\"}");
        duena.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"INGRESO\",\"monto\":" + OTRO_INGRESO + ",\"concepto\":\"otro ingreso\"}");
    }

    /**
     * El orden importa: en este punto la única sesión que existe es la abierta, así
     * que cualquier aparición de la clave {@code efectivoEsperado} es necesariamente
     * una fuga. Con una sesión cerrada en la base, esa clave aparecería legítimamente —
     * su número ya se reveló al cerrarla — y la afirmación perdería filo.
     */
    @Test
    @Order(1)
    void ningunEndpointDeLecturaRevelaElEsperado() {
        List<String> rutas = rutasDeLecturaDeLaApi();
        List<String> fugas = new ArrayList<>();

        System.out.println("VERIFICACION barriendo " + rutas.size()
                + " endpoints de lectura con la sesión ABIERTA (esperado=" + ESPERADO + "):");

        for (String ruta : rutas) {
            Respuesta respuesta = duena.get(ruta);
            String cuerpo = respuesta.cuerpo() == null ? "" : respuesta.cuerpo();
            System.out.println("    " + respuesta.estado() + "  " + ruta);

            for (String prohibido : List.of(
                    String.valueOf(ESPERADO),
                    "efectivoEsperado")) {
                if (cuerpo.contains(prohibido)) {
                    fugas.add(ruta + " expone '" + prohibido + "' => " + cuerpo);
                }
            }
        }

        assertThat(fugas)
                .withFailMessage("Con la sesión abierta, estos endpoints revelan lo que el cierre "
                        + "a ciegas debe ocultar:%n%s", String.join("\n", fugas))
                .isEmpty();
    }

    /** Los GET de la API sin parámetros de consulta, con {id} resuelto a la sesión real. */
    private List<String> rutasDeLecturaDeLaApi() {
        List<String> rutas = new ArrayList<>();

        mapeos.getHandlerMethods().forEach((info, handler) -> {
            var patrones = info.getPathPatternsCondition();
            var metodos = info.getMethodsCondition().getMethods();
            if (patrones == null) {
                return;
            }
            boolean esLectura = metodos.isEmpty()
                    || metodos.stream().anyMatch(m -> m.name().equals(HttpMethod.GET.name()));
            if (!esLectura) {
                return;
            }
            for (var patron : patrones.getPatterns()) {
                String ruta = patron.getPatternString();
                if (ruta.startsWith("/api/")) {
                    rutas.add(ruta.replaceAll("\\{[^/}]+}", String.valueOf(idSesion)));
                }
            }
        });

        return rutas.stream().sorted().toList();
    }

    private Long extraerId(String json) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return buscador.find() ? Long.valueOf(buscador.group(1)) : null;
    }
}
