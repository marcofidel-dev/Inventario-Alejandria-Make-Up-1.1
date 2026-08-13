package com.alejandriamakeup.pos.compras;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ServicioCategoria;
import com.alejandriamakeup.pos.catalogo.ServicioMarca;
import com.alejandriamakeup.pos.catalogo.ServicioProducto;
import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Recibir una compra: lo que entra al ledger, lo que le pasa al costo promedio, y
 * lo que la previa anuncia antes de confirmar.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecepcionDeCompraTest {

    private static final String URL = BaseDatosAislada.urlNueva("recepcion-compra");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    private static final long PRECIO = 30_000;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private VarianteRepository varianteRepository;
    @Autowired private MovimientoInventarioRepository movimientoRepository;

    private static Producto producto;
    private ClienteHttpDePrueba duena;
    private long idProveedor;

    @BeforeEach
    void entrar() {
        if (producto == null) {
            Usuario usuario = new Usuario();
            usuario.setNombre("Alejandra");
            usuario.setRol(Rol.DUENA);
            usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            usuario.setActivo(true);
            usuario.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(usuario);

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        idProveedor = idDe(duena.post("/api/v1/proveedores",
                "{\"nombre\":\"Proveedor " + java.util.UUID.randomUUID() + "\"}"));
    }

    @Test
    void recibirEscribeUnMovimientoPorLineaYDejaElStock() {
        long variante = nuevaVariante();
        long id = idDe(crearBorrador(linea(variante, 7, 10_000)));

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/recepcion");
        long stock = movimientoRepository.stockDe(variante);

        System.out.println("VERIFICACION recepción => estado "
                + extraer(respuesta.cuerpo(), "estado") + ", stock " + stock);
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"estado\":\"RECIBIDA\"")
                .contains("\"fechaRecepcion\":");
        assertThat(stock).isEqualTo(7);
        assertThat(movimientoRepository.findByCompraId(id)).hasSize(1);
    }

    /**
     * El costo promedio queda en lo que dice el ledger, y es lo mismo que la previa
     * había anunciado.
     *
     * <p>La variante entra sin existencias, así que la primera recepción fija el
     * promedio en el costo de la compra: 10.000. La segunda lo pondera contra lo que
     * ya había: {@code (7 × 10.000 + 3 × 20.000) / 10 = 13.000}.
     */
    @Test
    void recibirRecalculaElCostoPromedioPonderado() {
        long variante = nuevaVariante();
        duena.post("/api/v1/compras/" + idDe(crearBorrador(linea(variante, 7, 10_000)))
                + "/recepcion");

        long id = idDe(crearBorrador(linea(variante, 3, 20_000)));
        Respuesta previa = duena.get("/api/v1/compras/" + id + "/previa-recepcion");
        duena.post("/api/v1/compras/" + id + "/recepcion");

        long guardado = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();

        System.out.println("VERIFICACION costo promedio tras recibir => " + guardado
                + " | previa: " + previa.cuerpo());
        assertThat(guardado).isEqualTo(13_000);
        assertThat(previa.cuerpo())
                .contains("\"costoPromedioActual\":10000")
                .contains("\"costoPromedioResultante\":13000");
    }

    /**
     * <strong>La doble recepción no duplica el inventario.</strong>
     *
     * <p>Dos POST seguidos —un doble clic, un reintento tras un timeout, la pestaña
     * abierta dos veces— son el fallo más caro que puede tener este módulo: el stock
     * queda inflado y nadie se entera hasta que alguien cuenta físicamente, semanas
     * después, sin ninguna pista de qué pasó.
     *
     * <p>La comprobación de estado debería bastar, pero "debería bastar" no es una
     * garantía: aquí se cuentan los movimientos del ledger antes y después de la
     * segunda llamada y se exige que sean los mismos.
     */
    @Test
    void unaSegundaRecepcionDaConflictoYNoTocaElLedger() {
        long variante = nuevaVariante();
        long id = idDe(crearBorrador(linea(variante, 5, 10_000)));

        duena.post("/api/v1/compras/" + id + "/recepcion");

        long movimientosAntes = movimientoRepository.count();
        long stockAntes = movimientoRepository.stockDe(variante);
        long costoAntes = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();

        Respuesta segunda = duena.post("/api/v1/compras/" + id + "/recepcion");

        long movimientosDespues = movimientoRepository.count();
        long stockDespues = movimientoRepository.stockDe(variante);
        long costoDespues = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();

        System.out.println("VERIFICACION doble recepción => " + segunda.estado()
                + " | movimientos " + movimientosAntes + " -> " + movimientosDespues
                + ", stock " + stockAntes + " -> " + stockDespues);

        assertThat(segunda.estado()).isEqualTo(409);
        assertThat(segunda.cuerpo())
                .contains("\"codigo\":\"ESTADO_DE_COMPRA_INVALIDO\"")
                .contains("solo se recibe una vez");
        assertThat(movimientosDespues).isEqualTo(movimientosAntes);
        assertThat(stockDespues).isEqualTo(stockAntes).isEqualTo(5);
        assertThat(costoDespues).isEqualTo(costoAntes);
    }

    /** La previa no escribe nada: se puede pedir dos veces y el ledger sigue igual. */
    @Test
    void laPreviaNoEscribeEnElLedger() {
        long variante = nuevaVariante();
        long id = idDe(crearBorrador(linea(variante, 4, 9_000)));

        long antes = movimientoRepository.count();
        duena.get("/api/v1/compras/" + id + "/previa-recepcion");
        duena.get("/api/v1/compras/" + id + "/previa-recepcion");
        long despues = movimientoRepository.count();

        System.out.println("VERIFICACION previa sin efectos => movimientos "
                + antes + " -> " + despues);
        assertThat(despues).isEqualTo(antes);
        assertThat(varianteRepository.findById(variante).orElseThrow().getCostoPromedio()).isZero();
    }

    /**
     * Las dos advertencias que pide la pantalla, calculadas en el servidor.
     *
     * <p>Con precio 30.000: un costo de 25.000 deja el margen en 16% —por debajo del
     * 20% del sistema— y un costo de 35.000 lo deja por encima del precio, que es
     * perder plata en cada venta.
     */
    @Test
    void laPreviaAvisaDelMargenBajoYDelCostoPorEncimaDelPrecio() {
        long margenJusto = nuevaVariante();
        long bajoElCosto = nuevaVariante();
        long sano = nuevaVariante();

        long id = idDe(crearBorrador(
                linea(margenJusto, 1, 25_000), linea(bajoElCosto, 1, 35_000), linea(sano, 1, 10_000)));

        Respuesta previa = duena.get("/api/v1/compras/" + id + "/previa-recepcion");

        System.out.println("VERIFICACION advertencias de la previa => " + previa.cuerpo());
        assertThat(previa.cuerpo()).contains("\"hayAdvertencias\":true");
        assertThat(bloqueDe(previa.cuerpo(), margenJusto))
                .contains("\"margenBajo\":true")
                .contains("\"costoSuperaPrecio\":false")
                .contains("\"margenPorcentaje\":17");
        assertThat(bloqueDe(previa.cuerpo(), bajoElCosto))
                .contains("\"costoSuperaPrecio\":true")
                .contains("\"margenBajo\":true");
        assertThat(bloqueDe(previa.cuerpo(), sano))
                .contains("\"costoSuperaPrecio\":false")
                .contains("\"margenBajo\":false")
                .contains("\"margenPorcentaje\":67");
    }

    /** Una compra en borrador que perdió sus líneas no se puede recibir. */
    @Test
    void laPreviaDeUnaCompraYaRecibidaDaConflicto() {
        long variante = nuevaVariante();
        long id = idDe(crearBorrador(linea(variante, 1, 1_000)));
        duena.post("/api/v1/compras/" + id + "/recepcion");

        Respuesta previa = duena.get("/api/v1/compras/" + id + "/previa-recepcion");

        System.out.println("VERIFICACION previa de una recibida => " + previa.estado()
                + " " + previa.cuerpo());
        assertThat(previa.estado()).isEqualTo(409);
    }

    // ------------------------------------------------------------- utilidades

    private long nuevaVariante() {
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Tono " + java.util.UUID.randomUUID(), null, null,
                PRECIO, 0, null, null)).getId();
    }

    private Respuesta crearBorrador(String... lineas) {
        return duena.post("/api/v1/compras",
                "{\"proveedorId\":" + idProveedor + ",\"lineas\":[" + String.join(",", lineas) + "]}");
    }

    private String linea(long varianteId, int cantidad, long costo) {
        return "{\"varianteId\":" + varianteId + ",\"cantidad\":" + cantidad
                + ",\"costoUnitario\":" + costo + "}";
    }

    /** El trozo del JSON que corresponde a una variante, para afirmar sobre su línea. */
    private String bloqueDe(String json, long varianteId) {
        int desde = json.indexOf("\"varianteId\":" + varianteId);
        assertThat(desde).withFailMessage("No hay línea para la variante %s en %s", varianteId, json)
                .isNotNegative();
        int hasta = json.indexOf('}', desde);
        return json.substring(desde, hasta < 0 ? json.length() : hasta);
    }

    private long idDe(Respuesta respuesta) {
        return Long.parseLong(extraer(respuesta.cuerpo(), "id"));
    }

    private String extraer(String json, String campo) {
        var buscador = java.util.regex.Pattern
                .compile("\"" + campo + "\":\"?([^,\"}]+)\"?").matcher(json);
        if (!buscador.find()) {
            throw new IllegalStateException("No se encontró " + campo + " en " + json);
        }
        return buscador.group(1);
    }
}
