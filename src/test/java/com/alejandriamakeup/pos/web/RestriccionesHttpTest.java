package com.alejandriamakeup.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.CategoriaRepository;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.MarcaRepository;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ProductoRepository;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.VarianteRepository;

/**
 * Una variante duplicada tiene que salir como 409 con un mensaje que la dueña
 * pueda entender, no como 500 "Error interno del servidor".
 *
 * <p>El riesgo que cubre es concreto: con SQLite la violación llega envuelta en
 * {@code JpaSystemException}, así que el handler obvio
 * ({@code @ExceptionHandler(DataIntegrityViolationException.class)}) no se
 * dispararía y el usuario vería un 500 genérico ante un simple duplicado.
 *
 * <p>Los dos primeros tests recorren la cadena completa — SQLite, Hibernate,
 * Spring, el advice, HTTP — a través de un controlador que vive solo en las
 * fuentes de test: la fase de persistencia no expone endpoints de producción, y
 * este no debe convertirse en uno.
 */
@SpringBootTest(
        classes = {PosApplication.class, RestriccionesHttpTest.ControladorDeDuplicados.class},
        webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RestriccionesHttpTest {

    @LocalServerPort
    private int puerto;

    @Autowired
    private TraductorRestriccionesSqlite traductor;

    private RestTestClient cliente;

    @BeforeEach
    void configurarCliente() {
        cliente = RestTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
    }

    /** Índice sobre expresión: SQLite reporta el nombre del índice. */
    @Test
    void unaVarianteDuplicadaDa409ConMensajeUtil() {
        cliente.post().uri("/test-restricciones/variante-duplicada")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(String.class)
                .value(cuerpo -> {
                    System.out.println("VERIFICACION POST variante duplicada => 409 " + cuerpo);
                    assertThat(cuerpo).contains("Ya existe una variante de ese producto con el mismo tono y tamaño.");
                    assertThat(cuerpo).doesNotContain("Error interno del servidor");
                    // Mismo formato que el resto de los errores de la API: con codigo,
                    // para que el front no tenga que ramificar sobre el texto.
                    assertThat(cuerpo).contains("\"codigo\":\"UNICIDAD_VIOLADA\"");
                });
    }

    /** Índice sobre columnas desnudas: SQLite reporta la lista de columnas. */
    @Test
    void unaMarcaDuplicadaDa409ConMensajeUtil() {
        cliente.post().uri("/test-restricciones/marca-duplicada")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(String.class)
                .value(cuerpo -> {
                    System.out.println("VERIFICACION POST marca duplicada => 409 " + cuerpo);
                    assertThat(cuerpo).contains("Ya existe una marca con ese nombre.");
                });
    }

    /**
     * Un fallo de datos que no viene de una restricción no debe disfrazarse de
     * conflicto: es un error real y le toca 500.
     */
    @Test
    void unErrorDeDatosQueNoEsRestriccionNoSeTraduce() {
        var noRestriccion = new InvalidDataAccessResourceUsageException(
                "sintaxis mala", new SQLException("no such column: inventado"));

        assertThat(traductor.traducir(noRestriccion)).isEmpty();
    }

    /** El traductor identifica el código tipado, no el texto del mensaje. */
    @Test
    void elTraductorClasificaPorElCodigoDeResultadoDelDriver() {
        var unica = new org.sqlite.SQLiteException(
                "A UNIQUE constraint failed (UNIQUE constraint failed: index 'ux_variante_codigo_barras')",
                org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
        var chequeo = new org.sqlite.SQLiteException(
                "A CHECK constraint failed (CHECK constraint failed: precio_venta >= 0)",
                org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK);

        var problemaUnica = traductor.traducir(new RuntimeException("envuelta", unica)).orElseThrow();
        var problemaChequeo = traductor.traducir(new RuntimeException("envuelta", chequeo)).orElseThrow();

        System.out.println("VERIFICACION unique => " + problemaUnica.estado() + " / " + problemaUnica.mensaje());
        System.out.println("VERIFICACION check  => " + problemaChequeo.estado() + " / " + problemaChequeo.mensaje());

        assertThat(problemaUnica.estado()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problemaUnica.codigo()).isEqualTo("UNICIDAD_VIOLADA");
        assertThat(problemaUnica.mensaje()).isEqualTo("Ese código de barras ya está asignado a otra variante.");
        assertThat(problemaChequeo.estado()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problemaChequeo.codigo()).isEqualTo("VALOR_FUERA_DE_RANGO");
    }

    /**
     * Controlador exclusivo de las pruebas: provoca duplicados reales contra la
     * base para que la excepción atraviese toda la cadena.
     */
    @RestController
    @RequestMapping("/test-restricciones")
    static class ControladorDeDuplicados {

        private final MarcaRepository marcaRepository;
        private final CategoriaRepository categoriaRepository;
        private final ProductoRepository productoRepository;
        private final VarianteRepository varianteRepository;

        ControladorDeDuplicados(MarcaRepository marcaRepository,
                                CategoriaRepository categoriaRepository,
                                ProductoRepository productoRepository,
                                VarianteRepository varianteRepository) {
            this.marcaRepository = marcaRepository;
            this.categoriaRepository = categoriaRepository;
            this.productoRepository = productoRepository;
            this.varianteRepository = varianteRepository;
        }

        @PostMapping("/variante-duplicada")
        public void varianteDuplicada() {
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

            varianteRepository.saveAndFlush(variante(producto));
            varianteRepository.saveAndFlush(variante(producto));
        }

        @PostMapping("/marca-duplicada")
        public void marcaDuplicada() {
            String nombre = "Marca repetida " + UUID.randomUUID();
            marcaRepository.saveAndFlush(conNombre(nombre));
            marcaRepository.saveAndFlush(conNombre(nombre));
        }

        private Marca conNombre(String nombre) {
            Marca marca = new Marca();
            marca.setNombre(nombre);
            return marca;
        }

        private Variante variante(Producto producto) {
            Variante variante = new Variante();
            variante.setProducto(producto);
            variante.setPrecioVenta(38900);
            variante.setCostoPromedio(21000);
            variante.setFechaCreacion(LocalDateTime.now());
            return variante;
        }
    }
}
