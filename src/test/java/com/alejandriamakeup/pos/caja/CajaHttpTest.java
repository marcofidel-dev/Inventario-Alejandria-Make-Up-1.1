package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El ciclo completo de una sesión de caja, en orden, como pasa en la tienda.
 *
 * <p>Va ordenado y con base de datos propia porque prueba estado <strong>global</strong>:
 * en todo el sistema puede haber una sola sesión abierta, así que estos tests no son
 * independientes entre sí — son un día de trabajo. Contra la base compartida, una
 * corrida anterior que dejara una sesión abierta rompería la siguiente.
 *
 * <p>Las cuentas del escenario: un ingreso de apertura de 200.000 (el efectivo dejado
 * de ayer), un ingreso de 50.000, un retiro de 30.000 y un gasto de 20.000 — los tres
 * últimos suman 0 — así que el esperado es 200.000, la suma de los movimientos y nada
 * más. El conteo físico da 195.000 a propósito, para que la diferencia sea
 * −5.000 y no un cero que podría estar tapando un cálculo que no corre.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class CajaHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("caja");
    private static final Path BACKUPS = Path.of(System.getProperty("java.io.tmpdir"),
            "AlejandriaMakeUp-test-aislado", "backups-caja-" + UUID.randomUUID());

    private static final long EFECTIVO_DE_AYER = 200_000;
    private static final long BASE_HOSTIL = 999_000;
    private static final long ESPERADO = 200_000;
    private static final long CONTADO = 195_000;
    private static final long DIFERENCIA = -5_000;

    private static Long idSesion;

    @DynamicPropertySource
    static void entornoAislado(DynamicPropertyRegistry registro) {
        BACKUPS.toFile().mkdirs();
        registro.add("spring.datasource.url", () -> URL);
        registro.add("app.paths.backups", BACKUPS::toString);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SesionCajaRepository sesionRepository;

    private ClienteHttpDePrueba duena;
    private ClienteHttpDePrueba empleada;

    @BeforeEach
    void prepararUsuariasYClientes() {
        if (usuarioRepository.count() == 0) {
            crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");
        }
        duena = new ClienteHttpDePrueba(puerto);
        empleada = new ClienteHttpDePrueba(puerto);
        entrar(duena, "Alejandra", "1111");
        entrar(empleada, "Camila", "2222");
    }

    /** La ruta de la sugerencia de base se eliminó: sin base no hay nada que sugerir. */
    @Test
    @Order(1)
    void elEndpointDeSugerenciaDeAperturaYaNoExiste() {
        Respuesta respuesta = duena.get("/api/v1/caja/sesiones/sugerencia-apertura");

        System.out.println("VERIFICACION sugerencia-apertura => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        // Sin ruta propia, "sugerencia-apertura" cae en /sesiones/{id} y no es un id.
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("PARAMETRO_INVALIDO").doesNotContain("baseSugerida");
    }

    /**
     * Abrir no pide ni acepta nada. Jackson ignora lo que no conoce, así que el cliente
     * viejo que todavía mande {@code baseInicial} no falla — y por eso la afirmación
     * que importa no es el 201 sino que la base quedó en cero: mandarla no hace nada.
     */
    @Test
    @Order(2)
    void abrirCreaLaSesionEIgnoraUnaBaseInicialQueLleguePorSuCuenta() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones",
                "{\"baseInicial\":" + BASE_HOSTIL + "}");

        System.out.println("VERIFICACION abrir con baseInicial hostil => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("\"estado\":\"ABIERTA\"").contains("\"consecutivo\":\"S-");

        idSesion = extraerId(respuesta.cuerpo());
        assertThat(idSesion).isNotNull();
        // La columna sigue en la tabla como vestigio, y vale 0 siempre.
        assertThat(sesionRepository.findById(idSesion).orElseThrow().getBaseInicial()).isZero();
    }

    /**
     * El reemplazo de la base inicial: el efectivo que quedó de la noche anterior se
     * declara como un INGRESO manual con concepto explícito. Es una decisión consciente
     * de quien abre, y queda en el historial como cualquier otro ingreso.
     */
    @Test
    @Order(3)
    void elEfectivoDejadoDeAyerSeDeclaraConUnIngresoManual() {
        Respuesta respuesta = movimiento(duena, "INGRESO", EFECTIVO_DE_AYER,
                "efectivo dejado de sesión anterior");

        System.out.println("VERIFICACION ingreso de apertura => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("\"monto\":" + EFECTIVO_DE_AYER);
    }

    @Test
    @Order(5)
    void noSePuedeAbrirUnaSegundaSesion() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones", "{}");

        System.out.println("VERIFICACION segunda apertura => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SESION_YA_ABIERTA");
    }

    @Test
    @Order(6)
    void laSesionAbiertaNoRevelaElEsperado() {
        Respuesta respuesta = duena.get("/api/v1/caja/sesiones/actual");

        System.out.println("VERIFICACION sesión actual (ABIERTA) => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .doesNotContain("baseInicial")
                .doesNotContain("efectivoEsperado")
                .doesNotContain(String.valueOf(ESPERADO));
    }

    @Test
    @Order(7)
    void losMovimientosManualesLlevanElSignoDelTipo() {
        assertThat(movimiento(duena, "INGRESO", 50_000, "venta por fuera del sistema").estado()).isEqualTo(201);
        assertThat(movimiento(duena, "RETIRO", 30_000, "consignación al banco").estado()).isEqualTo(201);
        assertThat(movimiento(empleada, "GASTO", 20_000, "domicilio de la mensajería").estado()).isEqualTo(201);

        Respuesta lista = duena.get("/api/v1/caja/sesiones/" + idSesion + "/movimientos");

        System.out.println("VERIFICACION movimientos => " + lista.cuerpo());
        assertThat(lista.cuerpo())
                .contains("\"monto\":50000")
                .contains("\"monto\":-30000")
                .contains("\"monto\":-20000");
        // Una lista de movimientos nunca trae un total: ese total ES el esperado.
        assertThat(lista.cuerpo()).doesNotContain("total");
    }

    @Test
    @Order(8)
    void unMovimientoSinConceptoNoPasa() {
        Respuesta respuesta = duena.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"RETIRO\",\"monto\":1000,\"concepto\":\"\"}");

        System.out.println("VERIFICACION movimiento sin concepto => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("concepto");
    }

    @Test
    @Order(9)
    void unMovimientoDeVentaNoSeRegistraAMano() {
        Respuesta respuesta = movimiento(duena, "VENTA_EFECTIVO", 10_000, "intento a mano");

        System.out.println("VERIFICACION VENTA_EFECTIVO a mano => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("nace de una venta");
    }

    @Test
    @Order(10)
    void unMontoNegativoNoPasa() {
        Respuesta respuesta = movimiento(duena, "RETIRO", -5_000, "monto con signo");

        System.out.println("VERIFICACION monto negativo => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(400);
    }

    /** El momento de la verdad: la primera y única vez que aparecen los tres números. */
    @Test
    @Order(11)
    void cerrarRevelaEsperadoContadoYDiferencia() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones/" + idSesion + "/cierre", """
                {"conteo":[{"denominacion":100000,"cantidad":1},
                           {"denominacion":50000,"cantidad":1},
                           {"denominacion":20000,"cantidad":2},
                           {"denominacion":5000,"cantidad":1}],
                 "montoRetirado":45000,
                 "observaciones":"cierre del día"}
                """);

        System.out.println("VERIFICACION cierre => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        // La respuesta del cierre es un ArqueoDto: la sesión anidada más el desglose
        // por método de pago, que es el único sitio del sistema donde aparece. Va
        // vacío mientras no haya ventas.
        assertThat(respuesta.cuerpo())
                .contains("\"sesion\":{")
                .contains("\"ventasPorMetodo\":[]");
        assertThat(respuesta.cuerpo())
                .contains("\"estado\":\"CERRADA\"")
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA)
                .contains("\"usuarioCierre\":\"Alejandra\"")
                // Ni base inicial ni base para mañana: esos conceptos ya no existen.
                .doesNotContain("baseInicial")
                .doesNotContain("baseSiguiente");
    }

    @Test
    @Order(12)
    void unaSesionCerradaNoSeVuelveACerrar() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones/" + idSesion + "/cierre",
                "{\"conteo\":[{\"denominacion\":1000,\"cantidad\":1}],"
                        + "\"montoRetirado\":0}");

        System.out.println("VERIFICACION segundo cierre => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SESION_CERRADA");
    }

    @Test
    @Order(13)
    void sinSesionAbiertaNoSePuedenRegistrarMovimientos() {
        Respuesta respuesta = movimiento(duena, "INGRESO", 1_000, "después del cierre");

        System.out.println("VERIFICACION movimiento tras el cierre => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SIN_SESION_ABIERTA");
    }

    @Test
    @Order(14)
    void losValoresCongeladosSiguenIntactos() {
        Respuesta respuesta = duena.get("/api/v1/caja/sesiones/" + idSesion);

        System.out.println("VERIFICACION valores congelados => " + respuesta.cuerpo());
        assertThat(respuesta.cuerpo())
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA);
    }

    @Test
    @Order(16)
    void laEmpleadaNoVeLaSesionCerradaDeLaDuena() {
        Respuesta detalle = empleada.get("/api/v1/caja/sesiones/" + idSesion);
        Respuesta listado = empleada.get("/api/v1/caja/sesiones");

        System.out.println("VERIFICACION EMPLEADA sobre sesión ajena cerrada => " + detalle.estado()
                + " " + detalle.cuerpo());
        System.out.println("VERIFICACION listado de la EMPLEADA => " + listado.cuerpo());

        assertThat(detalle.estado()).isEqualTo(403);
        assertThat(detalle.cuerpo()).contains("SIN_PERMISO");
        assertThat(detalle.cuerpo()).doesNotContain(String.valueOf(ESPERADO));
        // Y su listado no la incluye.
        assertThat(listado.estado()).isEqualTo(200);
        assertThat(listado.cuerpo()).doesNotContain("\"id\":" + idSesion);
    }

    @Test
    @Order(17)
    void laDuenaSiVeElHistorialCompleto() {
        Respuesta listado = duena.get("/api/v1/caja/sesiones");

        System.out.println("VERIFICACION listado de la DUENA => " + listado.cuerpo());
        assertThat(listado.cuerpo()).contains("\"id\":" + idSesion);
    }

    /** Cerrar la caja dispara el respaldo, después del commit. */
    @Test
    @Order(18)
    void elCierreDejoUnRespaldo() throws Exception {
        try (Stream<Path> archivos = Files.list(BACKUPS)) {
            var respaldos = archivos.filter(p -> p.getFileName().toString().endsWith(".db")).toList();
            System.out.println("VERIFICACION respaldos tras el cierre => " + respaldos);
            assertThat(respaldos).isNotEmpty();
        }
    }

    // ------------------------------------------------------- notas de sesión

    /**
     * La explicación de la diferencia llega <strong>después</strong> de conocerla, y
     * eso es todo el punto: {@code observaciones} viajaba dentro de la petición de
     * cierre, o sea antes de que existiera nada que explicar.
     *
     * <p>Que se pueda anotar sobre una sesión ya cerrada no contradice su
     * inmutabilidad: la nota es una fila nueva en otra tabla. Lo que se comprueba
     * aquí es justamente eso — los tres valores congelados siguen exactamente donde
     * estaban después de anotar.
     */
    @Test
    @Order(19)
    void seAnotaSobreUnaSesionYaCerradaSinTocarSusMontos() {
        Respuesta nota = duena.post("/api/v1/caja/sesiones/" + idSesion + "/notas",
                "{\"texto\":\"Faltó registrar un domicilio de la tarde\"}");

        System.out.println("VERIFICACION nota sobre sesión cerrada => " + nota.estado()
                + " " + nota.cuerpo());
        assertThat(nota.estado()).isEqualTo(201);
        assertThat(nota.cuerpo())
                .contains("Faltó registrar un domicilio")
                .contains("\"usuario\":\"Alejandra\"");

        Respuesta sesion = duena.get("/api/v1/caja/sesiones/" + idSesion);
        System.out.println("VERIFICACION la sesión tras anotar => " + sesion.cuerpo());
        assertThat(sesion.cuerpo())
                .contains("\"efectivoEsperado\":" + ESPERADO)
                .contains("\"efectivoContado\":" + CONTADO)
                .contains("\"diferencia\":" + DIFERENCIA)
                .contains("Faltó registrar un domicilio");
    }

    /** Anotar sigue la misma regla que ver: no se explica lo que no se puede leer. */
    @Test
    @Order(20)
    void laEmpleadaNoAnotaSobreLaSesionCerradaDeLaDuena() {
        Respuesta respuesta = empleada.post("/api/v1/caja/sesiones/" + idSesion + "/notas",
                "{\"texto\":\"no debería poder\"}");

        System.out.println("VERIFICACION EMPLEADA anotando sesión ajena => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("SIN_PERMISO");
    }

    @Test
    @Order(21)
    void unaNotaVaciaNoPasa() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones/" + idSesion + "/notas",
                "{\"texto\":\"   \"}");

        System.out.println("VERIFICACION nota vacía => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("VALIDACION_FALLIDA");
    }

    private Respuesta movimiento(ClienteHttpDePrueba cliente, String tipo, long monto, String concepto) {
        return cliente.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"" + tipo + "\",\"monto\":" + monto + ",\"concepto\":\"" + concepto + "\"}");
    }

    private void entrar(ClienteHttpDePrueba cliente, String nombre, String pin) {
        Respuesta login = cliente.post("/api/v1/auth/login",
                "{\"nombre\":\"" + nombre + "\",\"pin\":\"" + pin + "\"}");
        assertThat(login.estado())
                .withFailMessage("No pudo entrar %s: %s", nombre, login.cuerpo())
                .isEqualTo(200);
    }

    private void crear(String nombre, Rol rol, String pin) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setRol(rol);
        usuario.setPinHash(new BCryptPasswordEncoder().encode(pin));
        usuario.setActivo(true);
        usuario.setFechaCreacion(LocalDateTime.now());
        usuarioRepository.save(usuario);
    }

    private Long extraerId(String json) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return buscador.find() ? Long.valueOf(buscador.group(1)) : null;
    }
}
