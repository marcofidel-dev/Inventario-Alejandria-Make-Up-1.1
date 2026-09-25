package com.alejandriamakeup.pos.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.caja.ServicioSesionCaja;
import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ServicioCategoria;
import com.alejandriamakeup.pos.catalogo.ServicioMarca;
import com.alejandriamakeup.pos.catalogo.ServicioProducto;
import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.ServicioVenta;
import com.alejandriamakeup.pos.ventas.dto.PeticionesVentas;

import jakarta.persistence.EntityManagerFactory;

/**
 * El número de consultas del panel no puede crecer con el tamaño del negocio.
 *
 * <p>Misma guarda que la del catálogo, y por la misma razón: con cinco productos de
 * prueba, un N+1 responde igual de rápido que una consulta agrupada. Aquí duele más,
 * porque el pool es de <strong>una</strong> conexión: las consultas no se solapan, se
 * hacen fila. Cincuenta esperas de dos milisegundos son una pantalla que tarda en
 * abrir, y eso solo se descubre cuando la tienda ya metió su inventario de verdad.
 *
 * <p>No mide tiempo — eso sería ruidoso y dependiente de la máquina. Mide cuántas
 * sentencias se preparan, con 5 variantes vendidas y con 50: el número tiene que ser
 * el mismo. Un conteo estable es una propiedad estructural del código.
 *
 * <p><strong>Cada variante lleva su propio producto, marca y categoría, y su propia
 * venta.</strong> No es adorno del fixture: con cincuenta variantes del mismo
 * producto, la caché de primer nivel de Hibernate resolvería las cuarenta y nueve
 * lecturas siguientes sin ir a la base y un N+1 real quedaría escondido detrás de la
 * caché.
 */
@SpringBootTest(classes = PosApplication.class)
@ActiveProfiles("test")
class PanelSinNMasUnoTest {

    private static final String URL = BaseDatosAislada.urlNueva("metricas-n-mas-uno");

    /**
     * Tres: las líneas vendidas, las variantes y el stock. El margen sobre ese número
     * está para no convertir en rojo cualquier consulta legítima que se agregue, pero
     * no alcanza para esconder un N+1.
     */
    private static final int MAXIMO_DE_CONSULTAS = 6;

    @DynamicPropertySource
    static void entornoConEstadisticas(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
        registro.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ServicioMarca servicioMarca;

    @Autowired
    private ServicioCategoria servicioCategoria;

    @Autowired
    private ServicioProducto servicioProducto;

    @Autowired
    private ServicioVariante servicioVariante;

    @Autowired
    private ServicioInventario servicioInventario;

    @Autowired
    private ServicioSesionCaja servicioSesionCaja;

    @Autowired
    private ServicioVenta servicioVenta;

    @Autowired
    private ServicioMetricas servicioMetricas;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static long idDuena;

    @Test
    void elNumeroDeConsultasNoCreceConElNegocio() {
        idDuena = crearDuena();
        servicioSesionCaja.abrir(idDuena);

        venderVariantesNuevas(5);
        long conCinco = sentenciasDeUnPanel();

        venderVariantesNuevas(45);
        long conCincuenta = sentenciasDeUnPanel();

        System.out.println("VERIFICACION sentencias preparadas => 5 ventas: " + conCinco
                + " | 50 ventas: " + conCincuenta);

        assertThat(servicioMetricas.panel(Periodo.DIA, LocalDate.now()).actual().ventas())
                .isEqualTo(50);
        assertThat(conCincuenta)
                .withFailMessage("El panel emitió %d sentencias con 50 ventas y %d con 5: el "
                        + "número crece con el tamaño, o sea que hay un N+1. Con pool de una "
                        + "conexión eso se siente en la pantalla.", conCincuenta, conCinco)
                .isEqualTo(conCinco);

        assertThat(conCincuenta).isLessThanOrEqualTo(MAXIMO_DE_CONSULTAS);
    }

    private long sentenciasDeUnPanel() {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(estadisticas.isStatisticsEnabled())
                .withFailMessage("Las estadísticas de Hibernate no están activas: el test no mide nada")
                .isTrue();

        estadisticas.clear();
        servicioMetricas.panel(Periodo.DIA, LocalDate.now());
        return estadisticas.getPrepareStatementCount();
    }

    /** Cada una con su producto, marca y categoría propios, y su propia venta. */
    private void venderVariantesNuevas(int cuantas) {
        for (int i = 0; i < cuantas; i++) {
            String sufijo = String.valueOf(System.nanoTime());

            Marca marca = servicioMarca.crear("Marca " + sufijo);
            Categoria categoria = servicioCategoria.crear("Categoría " + sufijo);
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Producto " + sufijo, marca.getId(), categoria.getId(), null));
            Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Tono " + sufijo, "5 ml", null, 30_000L, 2, null, null));

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(variante.getId(), 10, 20_000L))),
                    idDuena);

            servicioVenta.registrar(new PeticionesVentas.Venta(
                    "venta-" + sufijo, MetodoPago.EFECTIVO, null, 100_000L,
                    List.of(new PeticionesVentas.Venta.Linea(variante.getId(), 1))), idDuena);
        }
    }

    private long crearDuena() {
        Usuario usuario = new Usuario();
        usuario.setNombre("Alejandra");
        usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
        usuario.setRol(Rol.DUENA);
        usuario.setActivo(true);
        usuario.setFechaCreacion(Fechas.ahora());
        return usuarioRepository.save(usuario).getId();
    }
}
