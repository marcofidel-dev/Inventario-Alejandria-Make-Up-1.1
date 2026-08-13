package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Un día de caja completo, en un solo test.
 *
 * <p>Las reglas de caja también están cubiertas una por una en {@code CajaHttpTest},
 * repartidas en dieciocho métodos ordenados. Este test existe aparte porque hay una
 * afirmación que solo se puede hacer recorriendo la secuencia entera de un tirón: que
 * los tres números del arqueo aparecen <strong>por primera vez</strong> en la
 * respuesta del cierre. "Por primera vez" no es una propiedad de un endpoint, es una
 * propiedad del recorrido, y un test partido en métodos independientes no la puede
 * demostrar.
 *
 * <p>Las cuentas: base 250.000, retiro de 60.000, ingreso de 35.000 → los movimientos
 * suman −25.000 y el esperado es 225.000. El conteo físico da 220.000, así que la
 * diferencia es −5.000. Ninguno de los tres números se repite ni coincide con un id,
 * una fecha o un consecutivo.
 *
 * <p>Sobre "ningún endpoint expone montos": los montos de los <em>movimientos</em> sí
 * se exponen, por decisión explícita — la cajera necesita verificar lo que registró.
 * Lo que no puede salir es lo que permite deducir el arqueo: la base inicial, el
 * esperado, o cualquier total.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CicloDeCajaExtremoAExtremoTest {

    private static final String URL = BaseDatosAislada.urlNueva("ciclo-extremo-a-extremo");

    private static final long BASE = 250_000;
    private static final long RETIRO = 60_000;
    private static final long INGRESO = 35_000;
    private static final long ESPERADO = BASE - RETIRO + INGRESO;   // 225.000
    private static final long CONTADO = 220_000;
    private static final long DIFERENCIA = CONTADO - ESPERADO;      // −5.000

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

    private ClienteHttpDePrueba cajera;

    @BeforeEach
    void prepararCajera() {
        if (usuarioRepository.count() == 0) {
            Usuario camila = new Usuario();
            camila.setNombre("Camila");
            camila.setRol(Rol.EMPLEADA);
            camila.setPinHash(new BCryptPasswordEncoder().encode("2222"));
            camila.setActivo(true);
            camila.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(camila);
        }
        cajera = new ClienteHttpDePrueba(puerto);
        assertThat(cajera.post("/api/v1/auth/login",
                "{\"nombre\":\"Camila\",\"pin\":\"2222\"}").estado()).isEqualTo(200);
    }

    @Test
    void unDiaDeCajaDePrincipioAFin() {
        // ── 1. Abrir ──────────────────────────────────────────────────────────
        Respuesta apertura = cajera.post("/api/v1/caja/sesiones",
                "{\"baseInicial\":" + BASE + ",\"observaciones\":\"apertura\"}");
        System.out.println("PASO 1  abrir con base " + BASE + " => " + apertura.estado()
                + " " + apertura.cuerpo());
        assertThat(apertura.estado()).isEqualTo(201);
        assertThat(apertura.cuerpo()).contains("\"estado\":\"ABIERTA\"");
        long id = extraerId(apertura.cuerpo());

        // ── 2. Un retiro y un ingreso ─────────────────────────────────────────
        Respuesta retiro = cajera.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"RETIRO\",\"monto\":" + RETIRO + ",\"concepto\":\"consignación\"}");
        Respuesta ingreso = cajera.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"INGRESO\",\"monto\":" + INGRESO + ",\"concepto\":\"reintegro\"}");
        System.out.println("PASO 2  retiro " + RETIRO + " => " + retiro.cuerpo());
        System.out.println("        ingreso " + INGRESO + " => " + ingreso.cuerpo());
        assertThat(retiro.estado()).isEqualTo(201);
        assertThat(ingreso.estado()).isEqualTo(201);
        // El signo lo puso el servidor, no el cliente.
        assertThat(retiro.cuerpo()).contains("\"monto\":-" + RETIRO);
        assertThat(ingreso.cuerpo()).contains("\"monto\":" + INGRESO);

        // ── 3. Con la caja abierta, nada revela el arqueo ──────────────────────
        List<String> fugas = new ArrayList<>();
        List<String> rutas = rutasDeLecturaDeLaApi(id);
        for (String ruta : rutas) {
            String cuerpo = cajera.get(ruta).cuerpo();
            cuerpo = cuerpo == null ? "" : cuerpo;
            for (String prohibido : List.of(String.valueOf(BASE), String.valueOf(ESPERADO),
                    "baseInicial", "efectivoEsperado", "efectivoContado", "diferencia")) {
                if (cuerpo.contains(prohibido)) {
                    fugas.add(ruta + " expone '" + prohibido + "'");
                }
            }
        }
        System.out.println("PASO 3  barridos " + rutas.size() + " endpoints de lectura => "
                + (fugas.isEmpty() ? "ninguna fuga" : fugas));
        assertThat(fugas)
                .withFailMessage("Con la caja abierta se filtró el arqueo: %s", fugas)
                .isEmpty();

        // Los montos de los movimientos sí se ven: es la decisión tomada.
        Respuesta movimientos = cajera.get("/api/v1/caja/sesiones/" + id + "/movimientos");
        assertThat(movimientos.cuerpo()).contains("\"monto\":-" + RETIRO).contains("\"monto\":" + INGRESO);

        // ── 4. El conteo, y con él los tres números por primera vez ───────────
        Respuesta cierre = cajera.post("/api/v1/caja/sesiones/" + id + "/cierre", """
                {"conteo":[{"denominacion":100000,"cantidad":2},
                           {"denominacion":20000,"cantidad":1}],
                 "montoRetirado":100000,
                 "baseSiguiente":120000,
                 "observaciones":"cierre del día"}
                """);
        System.out.println("PASO 4  conteo enviado (2×100.000 + 1×20.000 = " + CONTADO + ") => "
                + cierre.estado() + " " + cierre.cuerpo());
        assertThat(cierre.estado()).isEqualTo(200);
        assertThat(cierre.cuerpo())
                .contains("\"estado\":\"CERRADA\"")
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA);

        // ── 5. Congelados: se releen iguales ──────────────────────────────────
        Respuesta relectura = cajera.get("/api/v1/caja/sesiones/" + id);
        System.out.println("PASO 5  relectura => " + relectura.cuerpo());
        assertThat(relectura.cuerpo())
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA)
                .contains("\"montoRetirado\":100000")
                .contains("\"baseSiguiente\":120000");

        // ── 6. Cerrada es inmutable ───────────────────────────────────────────
        Respuesta segundoCierre = cajera.post("/api/v1/caja/sesiones/" + id + "/cierre",
                "{\"conteo\":[{\"denominacion\":1000,\"cantidad\":1}],"
                        + "\"montoRetirado\":0,\"baseSiguiente\":0}");
        Respuesta movimientoTardio = cajera.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"INGRESO\",\"monto\":1000,\"concepto\":\"después del cierre\"}");
        System.out.println("PASO 6  segundo cierre => " + segundoCierre.estado()
                + " " + segundoCierre.cuerpo());
        System.out.println("        movimiento tardío => " + movimientoTardio.estado()
                + " " + movimientoTardio.cuerpo());
        assertThat(segundoCierre.estado()).isEqualTo(409);
        assertThat(segundoCierre.cuerpo()).contains("SESION_CERRADA");
        assertThat(movimientoTardio.estado()).isEqualTo(409);
        assertThat(movimientoTardio.cuerpo()).contains("SIN_SESION_ABIERTA");

        // Y el intento fallido no alteró nada.
        Respuesta despues = cajera.get("/api/v1/caja/sesiones/" + id);
        assertThat(despues.cuerpo())
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA);
        assertThat(despues.cuerpo()).doesNotContain("después del cierre");
        System.out.println("PASO 6  valores tras los intentos de modificación => intactos");
    }

    private List<String> rutasDeLecturaDeLaApi(long idSesion) {
        List<String> rutas = new ArrayList<>();
        mapeos.getHandlerMethods().forEach((info, handler) -> {
            var patrones = info.getPathPatternsCondition();
            if (patrones == null) {
                return;
            }
            var metodos = info.getMethodsCondition().getMethods();
            if (!metodos.isEmpty()
                    && metodos.stream().noneMatch(m -> m.name().equals(HttpMethod.GET.name()))) {
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

    private long extraerId(String json) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        assertThat(buscador.find()).isTrue();
        return Long.parseLong(buscador.group(1));
    }
}
