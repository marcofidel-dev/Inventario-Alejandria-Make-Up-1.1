package com.alejandriamakeup.pos.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;

import jakarta.persistence.EntityManagerFactory;

/**
 * El número de consultas del catálogo no puede crecer con el número de variantes.
 *
 * <p>Esta es la clase de fallo que no se ve en desarrollo: con tres productos de
 * prueba, un N+1 responde igual de rápido que una consulta agrupada. Se nota cuando la
 * tienda ya metió su inventario de verdad y la pantalla del mostrador tarda tres
 * segundos en abrir, que es exactamente el momento en que nadie quiere estar
 * depurando.
 *
 * <p>El test no mide tiempo — eso sería una prueba de rendimiento, ruidosa y
 * dependiente de la máquina. Mide <strong>cuántas sentencias se preparan</strong> con
 * las estadísticas de Hibernate, y compara 5 variantes contra 50: el número tiene que
 * ser el mismo. Un conteo estable es una propiedad estructural del código, no del
 * hardware.
 *
 * <p><strong>Cada variante tiene su propio producto, marca y categoría</strong>, y eso
 * no es decoración del fixture: es lo que hace que el test sirva. Con cincuenta
 * variantes de un mismo producto, la caché de primer nivel de Hibernate resuelve las
 * cuarenta y nueve lecturas siguientes sin ir a la base, así que un N+1 real quedaría
 * escondido detrás de la caché y el conteo saldría constante igual. Lo descubrí
 * intentando romper el test: metí un N+1 de verdad y siguió pasando.
 *
 * <p>Dos cosas más que salieron de intentar romperlo, y que conviene saber antes de
 * tocar {@code ServicioCatalogoCompleto}:
 *
 * <ul>
 *   <li>Leer {@code getId()} de una asociación perezosa <strong>no</strong> cuesta una
 *       consulta: el proxy ya conoce su identificador. Cuesta leer cualquier otro campo.
 *   <li>Las marcas y categorías se cargan enteras al principio del método, así que
 *       quedan en la caché de sesión y cualquier acceso perezoso a ellas sale gratis.
 *       Eso hace que el lado de los productos sea estructuralmente inmune. La
 *       superficie que queda es el lado de las variantes, donde los productos vienen
 *       como proyección y por tanto <em>no</em> están en la sesión: ahí un
 *       {@code v.getProducto().getNombre()} sí cuesta una consulta por fila, y es lo
 *       que este test detecta (comprobado: 10 sentencias con 5 variantes, 55 con 50).
 * </ul>
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class CatalogoSinNMasUnoTest {

    private static final String URL = BaseDatosAislada.urlNueva("catalogo-n-mas-uno");

    @DynamicPropertySource
    static void entornoConEstadisticas(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
        registro.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
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
    private EntityManagerFactory entityManagerFactory;

    @Test
    void elNumeroDeConsultasNoCreceConLasVariantes() {
        crearProductosConSuVariante(5);
        long conCinco = sentenciasDeUnCatalogoCompleto();

        crearProductosConSuVariante(45);
        long conCincuenta = sentenciasDeUnCatalogoCompleto();

        System.out.println("VERIFICACION sentencias preparadas => 5 variantes: " + conCinco
                + " | 50 variantes: " + conCincuenta);

        assertThat(servicioCatalogo.completo().variantes()).hasSize(50);
        assertThat(conCincuenta)
                .withFailMessage("El catálogo emitió %d sentencias con 50 variantes y %d con 5: "
                        + "el número crece con el tamaño, o sea que hay un N+1.",
                        conCincuenta, conCinco)
                .isEqualTo(conCinco);

        // Y que además sea un número pequeño y no "constante pero absurdo".
        assertThat(conCincuenta).isLessThanOrEqualTo(8);
    }

    /** Las mismas cinco consultas, contadas en limpio. */
    private long sentenciasDeUnCatalogoCompleto() {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(estadisticas.isStatisticsEnabled())
                .withFailMessage("Las estadísticas de Hibernate no están activas: el test no mide nada")
                .isTrue();

        estadisticas.clear();
        servicioCatalogo.completo();
        return estadisticas.getPrepareStatementCount();
    }

    /**
     * Cada variante con su propio producto, marca y categoría, para que un N+1 no pueda
     * esconderse detrás de la caché de primer nivel.
     */
    private void crearProductosConSuVariante(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            String sufijo = String.valueOf(System.nanoTime());
            Marca marca = servicioMarca.crear("Marca " + sufijo);
            Categoria categoria = servicioCategoria.crear("Categoría " + sufijo);
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Producto " + sufijo, marca.getId(), categoria.getId(), null));
            servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Tono " + sufijo, "5 ml", null, 30_000L, 2, null, null));
        }
    }
}
