package com.alejandriamakeup.pos.ventas;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.alejandriamakeup.pos.catalogo.Variante;
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
 * Quién vende y quién anula.
 *
 * <p>La EMPLEADA vende: es su trabajo, y sin ese permiso no habría quien atendiera el
 * mostrador. Lo que no hace es anular, porque anular devuelve inventario y saca plata
 * del cajón del día sobre una venta que puede no ser suya. Deshacer y hacer no son la
 * misma capacidad.
 *
 * <p>Los costos los cubre además {@code FugaDeCostosTest}, que barre todos los GET de
 * la API. Aquí se comprueba lo que ese barrido no alcanza: el cuerpo de la respuesta
 * de un <strong>POST</strong> de venta, que es por donde el costo congelado saldría
 * más naturalmente.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class PermisosVentasHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("permisos-ventas");

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
    @Autowired private ServicioInventario servicioInventario;

    /** Irrepetible: si aparece en una respuesta, salió del costo congelado. */
    private static final long COSTO = 777_771;
    private static final long PRECIO = 38_900;

    private static Long labial;
    private static Long ventaDeLaEmpleada;

    private ClienteHttpDePrueba empleada;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void sembrarYEntrar() {
        if (labial == null) {
            long idDuena = crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));

            Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, PRECIO, 2, null, null));
            labial = variante.getId();

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(labial, 50, COSTO))), idDuena);
        }

        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    /** La EMPLEADA abre su caja y vende. Es el turno normal de la tienda. */
    @Test
    @Order(1)
    void laEmpleadaAbreCajaYVende() {
        Respuesta apertura = empleada.post("/api/v1/caja/sesiones", "{\"baseInicial\":100000}");
        assertThat(apertura.estado()).isEqualTo(201);

        Respuesta venta = empleada.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":50000,\"lineas\":[{\"varianteId\":" + labial
                        + ",\"cantidad\":1}]}");

        System.out.println("VERIFICACION la EMPLEADA vende => " + venta.estado() + " "
                + venta.cuerpo());
        assertThat(venta.estado()).isEqualTo(201);
        ventaDeLaEmpleada = Long.parseLong(venta.cuerpo().replaceFirst("^\\{\"id\":(\\d+).*$", "$1"));

        // El POST es la puerta que el barrido de GET no cubre.
        assertThat(venta.cuerpo().toLowerCase())
                .withFailMessage("La respuesta del cobro menciona el costo: %s", venta.cuerpo())
                .doesNotContain("costo");
        assertThat(venta.cuerpo()).doesNotContain(String.valueOf(COSTO));
    }

    @Test
    @Order(2)
    void laEmpleadaPuedeLeerLaVentaQueAcabaDeHacerPeroSinCostos() {
        Respuesta respuesta = empleada.get("/api/v1/ventas/" + ventaDeLaEmpleada);

        System.out.println("VERIFICACION la EMPLEADA lee su venta => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo().toLowerCase()).doesNotContain("costo").doesNotContain("margen");
        assertThat(respuesta.cuerpo()).doesNotContain(String.valueOf(COSTO));
    }

    @Test
    @Order(3)
    void laEmpleadaNoAnula() {
        Respuesta respuesta = empleada.post("/api/v1/ventas/" + ventaDeLaEmpleada + "/anulacion",
                "{\"motivo\":\"me equivoque\"}");

        System.out.println("VERIFICACION la EMPLEADA anula => " + respuesta.estado() + " "
                + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("SIN_PERMISO");
    }

    /**
     * La contraparte. Sin esto, un sistema donde nadie pudiera anular pasaría los tres
     * anteriores y dejaría un cobro mal hecho sin forma de corregirse.
     */
    @Test
    @Order(4)
    void laDuenaSiAnula() {
        Respuesta respuesta = duena.post("/api/v1/ventas/" + ventaDeLaEmpleada + "/anulacion",
                "{\"motivo\":\"cobro mal hecho\"}");

        System.out.println("VERIFICACION la DUENA anula => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"estado\":\"ANULADA\"");
        // Y tampoco a ella le llega el costo congelado.
        assertThat(respuesta.cuerpo().toLowerCase()).doesNotContain("costo");
    }

    private long crear(String nombre, Rol rol, String pin) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setRol(rol);
        usuario.setPinHash(new BCryptPasswordEncoder().encode(pin));
        usuario.setActivo(true);
        usuario.setFechaCreacion(Fechas.ahora());
        return usuarioRepository.save(usuario).getId();
    }
}
