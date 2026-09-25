package com.alejandriamakeup.pos.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
import com.alejandriamakeup.pos.catalogo.Categoria;
import com.alejandriamakeup.pos.catalogo.Marca;
import com.alejandriamakeup.pos.catalogo.Producto;
import com.alejandriamakeup.pos.catalogo.ServicioCategoria;
import com.alejandriamakeup.pos.catalogo.ServicioMarca;
import com.alejandriamakeup.pos.catalogo.ServicioProducto;
import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El listado del día: donde se encuentra la venta que se va a anular.
 *
 * <p>Lo que se comprueba aquí es lo que no se ve mirando la pantalla: que el filtro
 * por fecha recorta de verdad —y que su rango cubre el día entero, extremos
 * incluidos—, que la venta anulada sigue apareciendo con su motivo en vez de
 * desaparecer, y que el resumen no arrastra ni costos ni líneas.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class ListadoDeVentasHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("listado-de-ventas");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    /** Irrepetible: si aparece en el listado, salió del costo congelado. */
    private static final long COSTO = 777_771;
    private static final long PRECIO = 38_900;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private VentaRepository ventaRepository;

    private static Long labial;
    private static Long idVentaAnulada;

    private ClienteHttpDePrueba duena;

    @BeforeEach
    void sembrarYEntrar() {
        if (labial == null) {
            Usuario usuaria = new Usuario();
            usuaria.setNombre("Alejandra");
            usuaria.setRol(Rol.DUENA);
            usuaria.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            usuaria.setActivo(true);
            usuaria.setFechaCreacion(Fechas.ahora());
            long idDuena = usuarioRepository.save(usuaria).getId();

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));
            labial = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, PRECIO, 2, null, null)).getId();

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(labial, 50, COSTO))), idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    @Order(1)
    void sinFechaSonLasDeHoyYSinCostosNiLineas() {
        duena.post("/api/v1/caja/sesiones", "{}");
        cobrar("EFECTIVO", 50_000L);
        cobrar("NEQUI", null);

        Respuesta respuesta = duena.get("/api/v1/ventas");

        System.out.println("VERIFICACION listado de hoy => " + respuesta.estado() + " "
                + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"consecutivo\":\"V-000001\"")
                .contains("\"consecutivo\":\"V-000002\"")
                .contains("\"metodoPago\":\"NEQUI\"")
                .contains("\"usuario\":\"Alejandra\"");

        // Ni costos ni líneas: el resumen no es un VentaDto recortado.
        assertThat(respuesta.cuerpo().toLowerCase()).doesNotContain("costo").doesNotContain("margen");
        assertThat(respuesta.cuerpo())
                .doesNotContain(String.valueOf(COSTO))
                .doesNotContain("\"lineas\"")
                .doesNotContain("variantesEnNegativo");
    }

    /**
     * El filtro recorta. Con las dos ventas de hoy en la base, pedir ayer tiene que
     * dar vacío: un filtro que se ignorara devolvería las mismas dos y nadie lo
     * notaría hasta tener meses de ventas encima.
     */
    @Test
    @Order(2)
    void elFiltroPorFechaRecorta() {
        String ayer = LocalDate.now().minusDays(1).toString();
        Respuesta respuesta = duena.get("/api/v1/ventas?fecha=" + ayer);

        System.out.println("VERIFICACION listado de " + ayer + " => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).isEqualTo("[]");

        // Y pedir hoy por su fecha explícita da lo mismo que no pedir nada.
        Respuesta hoy = duena.get("/api/v1/ventas?fecha=" + LocalDate.now());
        assertThat(hoy.cuerpo()).isEqualTo(duena.get("/api/v1/ventas").cuerpo());
    }

    /**
     * El rango cubre el día entero. Las fechas se guardan como TEXT de ancho fijo con
     * precisión de segundos, así que los dos extremos son {@code 00:00:00} y
     * {@code 23:59:59}: una venta a cualquiera de esas dos horas tiene que salir. Un
     * rango abierto por arriba dejaría fuera la última venta de la noche, que es la
     * que más falta hace cuadrar.
     */
    @Test
    @Order(3)
    void losExtremosDelDiaEntranEnElRango() {
        Venta primera = ventaRepository.findByConsecutivo("V-000001").orElseThrow();
        Venta segunda = ventaRepository.findByConsecutivo("V-000002").orElseThrow();
        primera.setFecha(LocalDate.now().atStartOfDay());
        segunda.setFecha(LocalDate.now().atTime(23, 59, 59));
        ventaRepository.save(primera);
        ventaRepository.save(segunda);

        Respuesta respuesta = duena.get("/api/v1/ventas");

        System.out.println("VERIFICACION extremos 00:00:00 y 23:59:59 => " + respuesta.cuerpo());
        assertThat(respuesta.cuerpo())
                .contains("\"consecutivo\":\"V-000001\"")
                .contains("\"consecutivo\":\"V-000002\"");
    }

    /**
     * Una venta anulada <strong>no desaparece del listado</strong>. Es lo contrario de
     * lo que haría un borrado: la venta existió, se cobró y se deshizo, y el listado
     * es donde queda constancia de las tres cosas junto con el motivo.
     */
    @Test
    @Order(4)
    void laVentaAnuladaSigueApareciendoConSuMotivo() {
        idVentaAnulada = ventaRepository.findByConsecutivo("V-000001").orElseThrow().getId();
        duena.post("/api/v1/ventas/" + idVentaAnulada + "/anulacion",
                "{\"motivo\":\"cobro mal hecho\"}");

        Respuesta respuesta = duena.get("/api/v1/ventas");

        System.out.println("VERIFICACION listado con una anulada => " + respuesta.cuerpo());
        assertThat(respuesta.cuerpo())
                .contains("\"consecutivo\":\"V-000001\"")
                .contains("\"estado\":\"ANULADA\"")
                .contains("cobro mal hecho")
                .contains("\"estado\":\"COMPLETADA\"");
    }

    private void cobrar(String metodo, Long recibido) {
        String efectivo = recibido == null ? "" : ",\"efectivoRecibido\":" + recibido;
        duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"" + metodo + "\""
                        + efectivo + ",\"lineas\":[{\"varianteId\":" + labial
                        + ",\"cantidad\":1}]}");
    }
}
