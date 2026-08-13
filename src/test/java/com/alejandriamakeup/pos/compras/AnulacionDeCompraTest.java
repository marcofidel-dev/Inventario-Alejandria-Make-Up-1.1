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
 * Anular una compra recibida: devolver el stock, aunque quede negativo, y dejar el
 * costo promedio como si la compra nunca hubiera ocurrido.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AnulacionDeCompraTest {

    private static final String URL = BaseDatosAislada.urlNueva("anulacion-compra");

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

    @Test
    void anularDevuelveElStockYDejaConstanciaDelMotivo() {
        long variante = nuevaVariante();
        long id = recibir(variante, 6, 10_000);

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/anulacion",
                "{\"motivo\":\"Llego mercancia equivocada\"}");

        long stock = movimientoRepository.stockDe(variante);

        System.out.println("VERIFICACION anulación => stock " + stock + " | " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"estado\":\"ANULADA\"")
                .contains("\"motivoBaja\":\"Llego mercancia equivocada\"")
                .contains("\"fechaBaja\":");
        assertThat(stock).isZero();
        // La entrada y su anulacion, las dos con el compra_id: es lo que permite
        // saltarlas juntas al recalcular.
        assertThat(movimientoRepository.findByCompraId(id)).hasSize(2);
    }

    /**
     * <strong>El stock puede quedar negativo, y se deja.</strong>
     *
     * <p>Entran 5, salen 3, y después se descubre que la compra nunca debió existir.
     * Al anularla el stock queda en −3, y eso es verdad: se vendieron tres unidades
     * que el sistema no tenía. Taparlo —recortando a cero, o bloqueando la anulación—
     * borraría el único rastro de que hay algo que averiguar.
     *
     * <p>El costo promedio queda en 0 porque, saltando la compra anulada, no queda
     * ninguna entrada de valor en el ledger: solo un ajuste negativo, que consume
     * pero no valora.
     */
    @Test
    void anularUnaCompraYaConsumidaDejaElStockNegativoYNoLoOculta() {
        long variante = nuevaVariante();
        long id = recibir(variante, 5, 10_000);

        duena.post("/api/v1/inventario/ajustes", "{\"varianteId\":" + variante
                + ",\"cantidad\":-3,\"motivo\":\"Salida de mercancia\"}");

        Respuesta previa = duena.get("/api/v1/compras/" + id + "/previa-anulacion");
        duena.post("/api/v1/compras/" + id + "/anulacion", "{\"motivo\":\"La compra nunca llego\"}");

        long stock = movimientoRepository.stockDe(variante);
        long costo = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();

        System.out.println("VERIFICACION anulación con stock negativo => stock " + stock
                + ", costo " + costo + " | previa: " + previa.cuerpo());

        assertThat(previa.cuerpo())
                .contains("\"hayStockNegativo\":true")
                .contains("\"stockActual\":2")
                .contains("\"stockResultante\":-3")
                .contains("\"quedaNegativo\":true");
        assertThat(stock).isEqualTo(-3);
        assertThat(costo).isZero();
    }

    /**
     * El promedio después de anular es el que dicta el ledger saltando la compra.
     *
     * <p>Dos compras recibidas, se anula la primera: queda solo la segunda, y como el
     * replay salta la anulada por completo, la segunda encuentra el stock en cero y
     * fija el promedio en su propio costo.
     */
    @Test
    void anularRecalculaElPromedioComoSiLaCompraNoHubieraOcurrido() {
        long variante = nuevaVariante();
        long primera = recibir(variante, 10, 5_000);
        recibir(variante, 10, 8_000);

        // Antes de anular, el promedio pondera las dos: (10x5000 + 10x8000)/20 = 6500.
        assertThat(varianteRepository.findById(variante).orElseThrow().getCostoPromedio())
                .isEqualTo(6_500);

        duena.post("/api/v1/compras/" + primera + "/anulacion", "{\"motivo\":\"Factura duplicada\"}");

        long guardado = varianteRepository.findById(variante).orElseThrow().getCostoPromedio();
        long segunElLedger = servicioCostoPromedio.calcular(variante);

        System.out.println("VERIFICACION promedio tras anular => guardado " + guardado
                + ", según el ledger " + segunElLedger);
        assertThat(guardado).isEqualTo(8_000).isEqualTo(segunElLedger);
        assertThat(movimientoRepository.stockDe(variante)).isEqualTo(10);
    }

    /** Sin motivo no hay anulación. */
    @Test
    void anularSinMotivoNoSePuede() {
        long variante = nuevaVariante();
        long id = recibir(variante, 2, 3_000);

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/anulacion", "{\"motivo\":\"\"}");

        System.out.println("VERIFICACION anular sin motivo => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"VALIDACION_FALLIDA\"");
        assertThat(movimientoRepository.stockDe(variante)).isEqualTo(2);
    }

    /** Una compra anulada no se vuelve a anular. */
    @Test
    void unaCompraAnuladaNoSeAnulaDosVeces() {
        long variante = nuevaVariante();
        long id = recibir(variante, 4, 1_000);
        duena.post("/api/v1/compras/" + id + "/anulacion", "{\"motivo\":\"primera\"}");

        long movimientosAntes = movimientoRepository.count();
        Respuesta segunda = duena.post("/api/v1/compras/" + id + "/anulacion",
                "{\"motivo\":\"segunda\"}");

        System.out.println("VERIFICACION doble anulación => " + segunda.estado()
                + " | movimientos " + movimientosAntes + " -> " + movimientoRepository.count());
        assertThat(segunda.estado()).isEqualTo(409);
        assertThat(movimientoRepository.count()).isEqualTo(movimientosAntes);
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
}
