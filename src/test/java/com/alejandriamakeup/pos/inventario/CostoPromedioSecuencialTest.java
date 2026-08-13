package com.alejandriamakeup.pos.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.compras.Compra;
import com.alejandriamakeup.pos.compras.CompraRepository;
import com.alejandriamakeup.pos.compras.EstadoCompra;
import com.alejandriamakeup.pos.compras.Proveedor;
import com.alejandriamakeup.pos.compras.ProveedorRepository;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El costo promedio ponderado, escenario por escenario.
 *
 * <p><strong>Todos los valores esperados están calculados a mano y escritos
 * literales.</strong> Si salieran de invocar la propia función, el test compararía
 * la implementación consigo misma y pasaría en verde con la fórmula equivocada —
 * que es exactamente lo que ya pasó una vez: el agregado
 * {@code Σ(cantidad×costo)/Σ(cantidad)} parece razonable, se sostiene en el caso
 * simple, y se derrumba en cuanto hay una venta entre dos compras.
 *
 * <p>Cada escenario usa su propia variante, así que ninguno puede contaminar a
 * otro. El ledger se arma con el repositorio directamente y no a través de los
 * servicios de compras: lo que se está probando es la aritmética sobre una
 * secuencia dada, y armarla a mano deja la secuencia a la vista en el test.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class CostoPromedioSecuencialTest {

    private static final String URL = BaseDatosAislada.urlNueva("costo-promedio");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @Autowired private ServicioCostoPromedio servicioCostoPromedio;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private MovimientoInventarioRepository movimientoRepository;
    @Autowired private VarianteRepository varianteRepository;
    @Autowired private CompraRepository compraRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;

    private static Usuario usuario;
    private static Proveedor proveedor;
    private static Producto producto;
    private static int siguienteTono;

    @BeforeEach
    void sembrar() {
        if (usuario != null) {
            return;
        }
        usuario = new Usuario();
        usuario.setNombre("Alejandra");
        usuario.setRol(Rol.DUENA);
        usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
        usuario.setActivo(true);
        usuario.setFechaCreacion(Fechas.ahora());
        usuario = usuarioRepository.save(usuario);

        proveedor = new Proveedor();
        proveedor.setNombre("Distribuciones Lopez");
        proveedor.setActivo(true);
        proveedor.setFechaCreacion(Fechas.ahora());
        proveedor = proveedorRepository.save(proveedor);

        Marca marca = servicioMarca.crear("Maybelline");
        Categoria categoria = servicioCategoria.crear("Labios");
        producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Labial mate", marca.getId(), categoria.getId(), null));
    }

    // ------------------------------------------------------------------ A

    /**
     * Compra 10 @ 5.000, venta 3, y se anula la compra.
     *
     * <p>Es el caso que hace explotar la fórmula agregada: {@code Σ(cantidad)} sobre
     * la compra y su anulación da cero, y el agregado divide por cero. El replay
     * secuencial simplemente salta los dos movimientos y no queda ninguna entrada
     * válida, así que el promedio es 0.
     *
     * <p>Y el stock queda en <strong>−3</strong>: tres unidades se vendieron y la
     * mercancía nunca llegó. El negativo no se corrige ni se bloquea — es
     * información verdadera, dice que el sistema vendió de más.
     */
    @Test
    void aAnularUnaCompraConsumidaEnParteNoDivideEntreCero() {
        Variante variante = nuevaVariante(20_000);
        Compra compra = nuevaCompra(EstadoCompra.ANULADA);
        LocalDateTime cuando = Fechas.ahora();

        entradaDeCompra(variante, compra, 10, 5_000, cuando);
        salida(variante, TipoMovimientoInventario.VENTA, -3, 5_000, cuando);
        anulacionDeCompra(variante, compra, -10, 5_000, cuando);

        long promedio = servicioCostoPromedio.calcular(variante.getId());
        long stock = movimientoRepository.stockDe(variante.getId());

        System.out.println("VERIFICACION escenario A => promedio " + promedio + ", stock " + stock);
        assertThat(promedio).isZero();
        assertThat(stock).isEqualTo(-3);
    }

    // ------------------------------------------------------------------ B

    /**
     * Compra 10 @ 5.000, venta 8, compra 10 @ 9.000.
     *
     * <p>El corazón del asunto. Al llegar la segunda compra solo quedaban
     * <strong>2</strong> unidades de la primera, no diez: las otras ocho ya se
     * vendieron y no pueden seguir pesando en el costo de lo que hay en la vitrina.
     *
     * <pre>
     *   correcto (móvil):  (2 × 5.000 + 10 × 9.000) / 12  = 8.333
     *   agregado (mal):    (10 × 5.000 + 10 × 9.000) / 20 = 7.000
     * </pre>
     *
     * <p>Un 16% de diferencia. Con el agregado, cada venta de esta variante
     * reportaría un margen que no existe.
     */
    @Test
    void bLasUnidadesYaVendidasNoPesanEnElPromedio() {
        Variante variante = nuevaVariante(20_000);
        LocalDateTime cuando = Fechas.ahora();

        entradaDeCompra(variante, nuevaCompra(EstadoCompra.RECIBIDA), 10, 5_000, cuando);
        salida(variante, TipoMovimientoInventario.VENTA, -8, 5_000, cuando);
        entradaDeCompra(variante, nuevaCompra(EstadoCompra.RECIBIDA), 10, 9_000, cuando);

        long promedio = servicioCostoPromedio.calcular(variante.getId());
        long stock = movimientoRepository.stockDe(variante.getId());

        System.out.println("VERIFICACION escenario B => promedio " + promedio + ", stock " + stock
                + " (el agregado habría dado 7000)");
        assertThat(promedio).isEqualTo(8_333);
        assertThat(promedio).isNotEqualTo(7_000);
        assertThat(stock).isEqualTo(12);
    }

    // ------------------------------------------------------------------ C

    /**
     * Tres compras, la primera y la tercera anuladas.
     *
     * <p>Solo la del medio cuenta, y como las anuladas se saltan por completo, la
     * que queda encuentra el stock en cero y fija el promedio en su propio costo.
     * El stock, en cambio, sale igual que si no se hubiera saltado nada: cada
     * anulación netea contra su compra.
     */
    @Test
    void cVariasComprasAnuladasIntercaladasSeSaltanEnteras() {
        Variante variante = nuevaVariante(30_000);
        Compra primera = nuevaCompra(EstadoCompra.ANULADA);
        Compra segunda = nuevaCompra(EstadoCompra.RECIBIDA);
        Compra tercera = nuevaCompra(EstadoCompra.ANULADA);
        LocalDateTime cuando = Fechas.ahora();

        entradaDeCompra(variante, primera, 10, 5_000, cuando);
        entradaDeCompra(variante, segunda, 10, 8_000, cuando);
        entradaDeCompra(variante, tercera, 10, 12_000, cuando);
        anulacionDeCompra(variante, primera, -10, 5_000, cuando);
        anulacionDeCompra(variante, tercera, -10, 12_000, cuando);

        long promedio = servicioCostoPromedio.calcular(variante.getId());
        long stock = movimientoRepository.stockDe(variante.getId());

        System.out.println("VERIFICACION escenario C => promedio " + promedio + ", stock " + stock);
        assertThat(promedio).isEqualTo(8_000);
        assertThat(stock).isEqualTo(10);
    }

    // ------------------------------------------------------------------ E

    /**
     * Carga inicial 10 @ 4.000, compra 10 @ 6.000, ajuste +5, y se anula la compra.
     *
     * <p><strong>Este es el escenario que más importa</strong>, porque es donde la
     * intuición se equivoca. El ajuste registró como costo unitario el promedio
     * vigente en ese momento —5.000, ya influido por la compra— y eso quedó escrito
     * en el ledger. Al anular la compra, el replay salta los movimientos de la
     * compra pero <strong>honra el costo que el ajuste dejó escrito</strong>:
     *
     * <pre>
     *   carga inicial:  stock 0  -> promedio 4.000, stock 10
     *   compra:         saltada
     *   ajuste +5:      (10 × 4.000 + 5 × 5.000) / 15 = 4.333, stock 15
     * </pre>
     *
     * <p>No vuelve a 4.000, y no es un error: el ledger es la fuente de verdad y
     * dice que ese ajuste valoró a 5.000. Queda escrito aquí porque es justo el
     * número que alguien "arreglaría" creyendo que hay un fallo.
     */
    @Test
    void eUnAjustePosteriorConservaElCostoQueRegistroAunqueSeAnuleLaCompra() {
        Variante variante = nuevaVariante(20_000);
        Compra compra = nuevaCompra(EstadoCompra.ANULADA);
        LocalDateTime cuando = Fechas.ahora();

        entrada(variante, TipoMovimientoInventario.CARGA_INICIAL, 10, 4_000, cuando);
        entradaDeCompra(variante, compra, 10, 6_000, cuando);
        entrada(variante, TipoMovimientoInventario.AJUSTE, 5, 5_000, cuando);
        anulacionDeCompra(variante, compra, -10, 6_000, cuando);

        long promedio = servicioCostoPromedio.calcular(variante.getId());
        long stock = movimientoRepository.stockDe(variante.getId());

        System.out.println("VERIFICACION escenario E => promedio " + promedio + ", stock " + stock
                + " (no vuelve a 4000: el ajuste registró 5000)");
        assertThat(promedio).isEqualTo(4_333);
        assertThat(stock).isEqualTo(15);
    }

    // ------------------------------------------------------------------ D

    /**
     * El invariante: lo guardado coincide con lo que dicta el ledger.
     *
     * <p>Es deliberadamente débil y conviene decirlo. Si el costo guardado saliera
     * siempre de {@code recalcular()}, esto compararía la función consigo misma. Lo
     * único que atrapa de verdad son los caminos que escriben el costo por otra vía,
     * y hoy hay uno: {@code ServicioInventario.cargaInicial()} fija
     * {@code costo_promedio = costoUnitario} sin pasar por aquí. Por eso el
     * escenario arranca justamente con una carga inicial.
     */
    @Test
    void dLoGuardadoPorOtrosCaminosCoincideConElLedger() {
        Variante variante = nuevaVariante(20_000);

        servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(variante.getId(), 12, 7_500L))),
                usuario.getId());

        long guardado = varianteRepository.findById(variante.getId()).orElseThrow().getCostoPromedio();
        long segunElLedger = servicioCostoPromedio.calcular(variante.getId());

        System.out.println("VERIFICACION invariante D => guardado " + guardado
                + ", según el ledger " + segunElLedger);
        assertThat(guardado).isEqualTo(segunElLedger).isEqualTo(7_500);
    }

    // ------------------------------------------------------------- utilidades

    private Variante nuevaVariante(long precioVenta) {
        siguienteTono += 1;
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Tono " + siguienteTono, null, null, precioVenta, 0, null, null));
    }

    private Compra nuevaCompra(EstadoCompra estado) {
        Compra compra = new Compra();
        compra.setConsecutivo("C-TEST-" + java.util.UUID.randomUUID());
        compra.setProveedor(proveedor);
        compra.setUsuario(usuario);
        compra.setTotal(0);
        compra.setFecha(Fechas.ahora());
        compra.setEstado(EstadoCompra.BORRADOR);
        if (estado.esBaja()) {
            // El CHECK de la V5 exige las tres columnas de baja juntas.
            compra.darDeBaja(estado, "motivo de prueba", usuario, Fechas.ahora());
        } else {
            compra.setEstado(estado);
        }
        return compraRepository.save(compra);
    }

    private void entradaDeCompra(Variante variante, Compra compra, int cantidad, long costo,
                                 LocalDateTime cuando) {
        movimientoRepository.save(MovimientoInventario.builder()
                .variante(variante).tipo(TipoMovimientoInventario.COMPRA)
                .cantidad(cantidad).costoUnitario(costo).compra(compra)
                .usuario(usuario).fecha(cuando).build());
    }

    private void anulacionDeCompra(Variante variante, Compra compra, int cantidad, long costo,
                                   LocalDateTime cuando) {
        movimientoRepository.save(MovimientoInventario.builder()
                .variante(variante).tipo(TipoMovimientoInventario.ANULACION)
                .cantidad(cantidad).costoUnitario(costo).compra(compra)
                .usuario(usuario).fecha(cuando).build());
    }

    private void entrada(Variante variante, TipoMovimientoInventario tipo, int cantidad, long costo,
                         LocalDateTime cuando) {
        movimientoRepository.save(MovimientoInventario.builder()
                .variante(variante).tipo(tipo).cantidad(cantidad).costoUnitario(costo)
                .usuario(usuario).fecha(cuando).build());
    }

    private void salida(Variante variante, TipoMovimientoInventario tipo, int cantidad, long costo,
                        LocalDateTime cuando) {
        entrada(variante, tipo, cantidad, costo, cuando);
    }
}
