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
import com.alejandriamakeup.pos.inventario.ServicioCostoPromedio;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Corregir una compra recibida: es anular -mismo stock devuelto, mismo recálculo de
 * promedio- más un borrador nuevo con las mismas líneas, listo para editar y volver a
 * recibir.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CorreccionDeCompraTest {

    private static final String URL = BaseDatosAislada.urlNueva("correccion-compra");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private VarianteRepository varianteRepository;
    @Autowired private MovimientoInventarioRepository movimientoRepository;
    @Autowired private ServicioCostoPromedio servicioCostoPromedio;

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

    /**
     * El caso central: corregir anula la original y deja un borrador con las mismas
     * líneas, sin tocar el consecutivo ni el total de la compra que se está
     * reemplazando — el total del borrador lo vuelve a sumar el servidor.
     */
    @Test
    void corregirAnulaYAbreUnBorradorConLasMismasLineas() {
        long variante = nuevaVariante();
        long id = recibir(variante, 6, 10_000);

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/correccion",
                "{\"motivo\":\"La cantidad se digitó mal\"}");

        System.out.println("VERIFICACION corrección => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"anulada\":")
                .contains("\"estado\":\"ANULADA\"")
                .contains("\"motivoBaja\":\"La cantidad se digitó mal\"")
                .contains("\"borrador\":")
                .contains("\"estado\":\"BORRADOR\"");

        // El stock vuelve a cero, igual que con una anulación normal.
        assertThat(movimientoRepository.stockDe(variante)).isZero();

        long idBorrador = idBorradorDe(respuesta.cuerpo());
        Respuesta borrador = duena.get("/api/v1/compras/" + idBorrador);
        assertThat(borrador.cuerpo())
                .contains("\"varianteId\":" + variante)
                .contains("\"cantidad\":6")
                .contains("\"costoUnitario\":10000")
                .contains("\"total\":60000");
    }

    /** Sin motivo no hay corrección: mismo CHECK que descarte y anulación. */
    @Test
    void corregirSinMotivoNoSePuede() {
        long variante = nuevaVariante();
        long id = recibir(variante, 2, 3_000);

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/correccion", "{\"motivo\":\"\"}");

        System.out.println("VERIFICACION corregir sin motivo => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"VALIDACION_FALLIDA\"");
        assertThat(movimientoRepository.stockDe(variante)).isEqualTo(2);
    }

    /** Solo se corrige una compra recibida: un borrador se descarta, no se corrige. */
    @Test
    void noSeCorrigeUnBorrador() {
        long variante = nuevaVariante();
        long id = idDe(duena.post("/api/v1/compras", "{\"proveedorId\":" + idProveedor
                + ",\"lineas\":[{\"varianteId\":" + variante + ",\"cantidad\":3"
                + ",\"costoUnitario\":5000}]}"));

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/correccion",
                "{\"motivo\":\"no aplica\"}");

        System.out.println("VERIFICACION corregir un borrador => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"ESTADO_DE_COMPRA_INVALIDO\"");
    }

    /**
     * El promedio tras corregir es el mismo que tras anular sola: corregir no
     * introduce una fórmula propia, pasa por el mismo
     * {@code ServicioCostoPromedio.recalcular()}.
     */
    @Test
    void elPromedioTrasCorregirEsElDeAnularSolo() {
        long variante = nuevaVariante();
        long primera = recibir(variante, 10, 5_000);
        recibir(variante, 10, 8_000);

        duena.post("/api/v1/compras/" + primera + "/correccion", "{\"motivo\":\"factura duplicada\"}");

        long guardado = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();
        long segunElLedger = servicioCostoPromedio.calcular(variante);

        System.out.println("VERIFICACION promedio tras corregir => guardado " + guardado
                + ", según el ledger " + segunElLedger);
        assertThat(guardado).isEqualTo(8_000).isEqualTo(segunElLedger);
    }

    // ------------------------------------------------------------- utilidades

    private long recibir(long varianteId, int cantidad, long costo) {
        long id = idDe(duena.post("/api/v1/compras", "{\"proveedorId\":" + idProveedor
                + ",\"lineas\":[{\"varianteId\":" + varianteId + ",\"cantidad\":" + cantidad
                + ",\"costoUnitario\":" + costo + "}]}"));
        duena.post("/api/v1/compras/" + id + "/recepcion");
        return id;
    }

    private long nuevaVariante() {
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Tono " + java.util.UUID.randomUUID(), null, null,
                30_000L, 0, null, null)).getId();
    }

    private long idDe(Respuesta respuesta) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(respuesta.cuerpo());
        if (!buscador.find()) {
            throw new IllegalStateException("Sin id en " + respuesta.cuerpo());
        }
        return Long.parseLong(buscador.group(1));
    }

    /** El id del borrador viaja dentro del objeto anidado {@code "borrador":{"id":N,...}}. */
    private long idBorradorDe(String cuerpo) {
        int desde = cuerpo.indexOf("\"borrador\":");
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(cuerpo.substring(desde));
        if (!buscador.find()) {
            throw new IllegalStateException("Sin id de borrador en " + cuerpo);
        }
        return Long.parseLong(buscador.group(1));
    }
}
