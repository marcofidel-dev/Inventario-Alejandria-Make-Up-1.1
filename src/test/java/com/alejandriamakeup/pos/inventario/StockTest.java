package com.alejandriamakeup.pos.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.CategoriaRepository;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.MarcaRepository;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ProductoRepository;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * El stock <strong>es</strong> la suma del ledger, no un campo. Aquí se comprueba
 * la suma con signo; que no exista ninguna columna de stock en el esquema lo
 * vigila {@code EsquemaMapeoTest}.
 *
 * <p>Cuidado al agregar tests a esta clase: es {@code @Transactional}, así que la
 * transacción ya tiene tomada la única conexión del pool
 * ({@code maximum-pool-size: 1}). Pedir un {@code dataSource.getConnection()}
 * adicional aquí no falla — se queda esperando 30 segundos y muere por timeout.
 * Los tests que necesiten JDBC crudo van en una clase sin
 * {@code @Transactional}.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
@Transactional
class StockTest {

    @Autowired
    private MovimientoInventarioRepository movimientoRepository;

    @Autowired
    private VarianteRepository varianteRepository;

    @Autowired
    private ProductoRepository productoRepository;

    @Autowired
    private MarcaRepository marcaRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void elStockEsLaSumaConSignoDelLedger() {
        Variante variante = nuevaVariante(0);
        Usuario usuario = nuevoUsuario();

        guardarMovimiento(variante, usuario, TipoMovimientoInventario.COMPRA, 20);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.VENTA, -3);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.VENTA, -5);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.MERMA, -2);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.ANULACION, 3);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.AJUSTE, -1);
        entityManager.flush();

        long stock = movimientoRepository.stockDe(variante.getId());
        System.out.println("VERIFICACION stock = 20 -3 -5 -2 +3 -1 => " + stock);
        assertThat(stock).isEqualTo(12);
    }

    @Test
    void unaVarianteSinMovimientosTieneStockCeroYNoNull() {
        Variante variante = nuevaVariante(0);
        entityManager.flush();

        long stock = movimientoRepository.stockDe(variante.getId());
        System.out.println("VERIFICACION stock de variante sin movimientos => " + stock);
        assertThat(stock).isZero();
    }

    @Test
    void unAjusteCorrigeSinTocarElMovimientoErrado() {
        Variante variante = nuevaVariante(0);
        Usuario usuario = nuevoUsuario();

        MovimientoInventario errado =
                guardarMovimiento(variante, usuario, TipoMovimientoInventario.COMPRA, 100);
        guardarMovimiento(variante, usuario, TipoMovimientoInventario.AJUSTE, -90);
        entityManager.flush();

        // El movimiento errado sigue ahí, intacto: la corrección es otra fila.
        assertThat(movimientoRepository.findById(errado.getId()).orElseThrow().getCantidad())
                .isEqualTo(100);
        assertThat(movimientoRepository.stockDe(variante.getId())).isEqualTo(10);
        assertThat(movimientoRepository.findByVarianteIdOrderByFechaAsc(variante.getId())).hasSize(2);
    }

    @Test
    void bajoMinimoDetectaLaVarianteQueQuedoCorta() {
        Variante conFaltante = nuevaVariante(10);
        Variante conSuficiente = nuevaVariante(2);
        Usuario usuario = nuevoUsuario();

        guardarMovimiento(conFaltante, usuario, TipoMovimientoInventario.COMPRA, 4);
        guardarMovimiento(conSuficiente, usuario, TipoMovimientoInventario.COMPRA, 8);
        entityManager.flush();
        entityManager.clear();

        List<Long> ids = varianteRepository.bajoMinimo().stream().map(Variante::getId).toList();
        System.out.println("VERIFICACION bajoMinimo: espera la de minimo 10 con 4 unidades ("
                + conFaltante.getId() + ") y no la de minimo 2 con 8 (" + conSuficiente.getId() + ")");
        assertThat(ids).contains(conFaltante.getId());
        assertThat(ids).doesNotContain(conSuficiente.getId());
    }

    private MovimientoInventario guardarMovimiento(Variante variante, Usuario usuario,
                                                   TipoMovimientoInventario tipo, int cantidad) {
        return movimientoRepository.save(MovimientoInventario.builder()
                .variante(variante)
                .tipo(tipo)
                .cantidad(cantidad)
                .costoUnitario(15000)
                .usuario(usuario)
                .fecha(LocalDateTime.now())
                .motivo("prueba")
                .build());
    }

    private Variante nuevaVariante(int stockMinimo) {
        String sufijo = UUID.randomUUID().toString();

        Marca marca = new Marca();
        marca.setNombre("Marca " + sufijo);
        marca = marcaRepository.save(marca);

        Categoria categoria = new Categoria();
        categoria.setNombre("Categoria " + sufijo);
        categoria = categoriaRepository.save(categoria);

        Producto producto = new Producto();
        producto.setNombre("Producto " + sufijo);
        producto.setMarca(marca);
        producto.setCategoria(categoria);
        producto.setFechaCreacion(LocalDateTime.now());
        producto = productoRepository.save(producto);

        Variante variante = new Variante();
        variante.setProducto(producto);
        variante.setTono("Tono " + sufijo);
        variante.setPrecioVenta(45000);
        variante.setCostoPromedio(20000);
        variante.setStockMinimo(stockMinimo);
        variante.setFechaCreacion(LocalDateTime.now());
        return varianteRepository.save(variante);
    }

    private Usuario nuevoUsuario() {
        Usuario usuario = new Usuario();
        usuario.setNombre("Usuario " + UUID.randomUUID());
        usuario.setPinHash("$2a$10$hashDePrueba");
        usuario.setRol(Rol.EMPLEADA);
        usuario.setFechaCreacion(LocalDateTime.now());
        return usuarioRepository.save(usuario);
    }
}
