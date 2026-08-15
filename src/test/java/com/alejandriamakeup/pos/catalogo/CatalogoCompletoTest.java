package com.alejandriamakeup.pos.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El catálogo completo: lo que trae, y sobre todo lo que no.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class CatalogoCompletoTest {

    private static final String URL = BaseDatosAislada.urlNueva("catalogo-completo");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @Autowired
    private ServicioCatalogoCompleto servicioCatalogo;

    @Autowired
    private ServicioMarca servicioMarca;

    @Autowired
    private ServicioCategoria servicioCategoria;

    @Autowired
    private ServicioProducto servicioProducto;

    @Autowired
    private ServicioVariante servicioVariante;

    @Autowired
    private MovimientoInventarioRepository movimientoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    // static: JUnit crea una instancia por metodo de test, asi que en campos de
    // instancia la guarda de "ya sembre" nunca se ve y el fixture se duplica.
    private static Long idConMovimientos;
    private static Long idSinMovimientos;

    @BeforeEach
    void sembrarCatalogo() {
        if (idConMovimientos != null) {
            return;
        }

        Usuario duena = new Usuario();
        duena.setNombre("Alejandra");
        duena.setRol(Rol.DUENA);
        duena.setPinHash("x");
        duena.setActivo(true);
        duena.setFechaCreacion(Fechas.ahora());
        duena = usuarioRepository.save(duena);

        Marca marca = servicioMarca.crear("Maybelline");
        Categoria categoria = servicioCategoria.crear("Labios");
        Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Labial mate", marca.getId(), categoria.getId(), "de larga duración"));

        Variante conStock = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Rojo clásico", "5 ml", "7501", 38_900L, 3, null, 12));
        Variante sinStock = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Nude rosado", "5 ml", null, 38_900L, 3, null, null));

        idConMovimientos = conStock.getId();
        idSinMovimientos = sinStock.getId();

        movimientoRepository.save(MovimientoInventario.builder()
                .variante(conStock).tipo(TipoMovimientoInventario.CARGA_INICIAL)
                .cantidad(12).costoUnitario(21_000).usuario(duena).fecha(Fechas.ahora()).build());
        movimientoRepository.save(MovimientoInventario.builder()
                .variante(conStock).tipo(TipoMovimientoInventario.AJUSTE)
                .cantidad(-2).costoUnitario(21_000).usuario(duena).fecha(Fechas.ahora())
                .motivo("rotas").build());
    }

    @Test
    void traeMarcasCategoriasProductosYVariantesEnUnaLlamada() {
        CatalogoDto catalogo = servicioCatalogo.completo();

        System.out.println("VERIFICACION catálogo => " + catalogo.marcas().size() + " marcas, "
                + catalogo.categorias().size() + " categorías, " + catalogo.productos().size()
                + " productos, " + catalogo.variantes().size() + " variantes");

        assertThat(catalogo.marcas()).hasSize(1);
        assertThat(catalogo.categorias()).hasSize(1);
        assertThat(catalogo.productos()).hasSize(1);
        assertThat(catalogo.variantes()).hasSize(2);
        assertThat(catalogo.productos().get(0).marcaId()).isEqualTo(catalogo.marcas().get(0).id());
    }

    @Test
    void elStockSaleDelLedgerConSuSigno() {
        long stock = variante(idConMovimientos).stock();

        System.out.println("VERIFICACION carga inicial de 12 y ajuste de -2 => stock " + stock);
        assertThat(stock).isEqualTo(10);
    }

    /**
     * Agotada y nunca recibida dan el mismo stock 0 y no son lo mismo: la segunda no
     * tiene costo real, y venderla congelaría costo 0 en la {@code VentaItem}. El
     * número no puede distinguirlas, así que la distinción viaja en
     * {@code conHistorial}, y es lo que el front usa para no listarla ni en el
     * catálogo ni en el buscador de venta.
     *
     * <p>La variante sin movimientos sigue viajando en la respuesta a propósito: las
     * dos pantallas por donde entra la mercancía —registrar una compra y la carga
     * inicial— acaban de crearla y tienen que poder elegirla. Si el servidor la
     * escondiera, la carga inicial se quedaría sin nada que cargar.
     */
    @Test
    void laVarianteSinMovimientosVieneMarcadaSinHistorial() {
        System.out.println("VERIFICACION conHistorial => con movimientos: "
                + variante(idConMovimientos).conHistorial() + ", sin movimientos: "
                + variante(idSinMovimientos).conHistorial());

        assertThat(variante(idSinMovimientos).conHistorial())
                .withFailMessage("Una variante sin ningún movimiento se marcó como con historial")
                .isFalse();
        assertThat(variante(idSinMovimientos).stock()).isZero();
        assertThat(variante(idConMovimientos).conHistorial()).isTrue();
    }

    @Test
    void elCatalogoNoTraeCostoPromedioEnNingunNivel() {
        String serializado = servicioCatalogo.completo().toString();

        System.out.println("VERIFICACION el catálogo serializado no menciona costos");
        assertThat(serializado)
                .doesNotContain("costo")
                .doesNotContain("Costo")
                .doesNotContain("21000");
    }

    /** Los inactivos también viajan: el front filtra, según la convención del proyecto. */
    @Test
    void losInactivosVienenConSuBandera() {
        servicioVariante.cambiarActivo(idSinMovimientos, false);

        CatalogoDto.VarianteDto inactiva = variante(idSinMovimientos);
        System.out.println("VERIFICACION variante desactivada sigue en el catálogo => activo="
                + inactiva.activo());
        assertThat(inactiva.activo()).isFalse();

        servicioVariante.cambiarActivo(idSinMovimientos, true);
    }

    private CatalogoDto.VarianteDto variante(Long id) {
        return servicioCatalogo.completo().variantes().stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La variante " + id + " no está en el catálogo"));
    }
}
