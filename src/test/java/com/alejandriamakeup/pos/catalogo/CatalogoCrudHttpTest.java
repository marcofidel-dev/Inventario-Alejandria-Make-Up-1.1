package com.alejandriamakeup.pos.catalogo;

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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/** El CRUD del catálogo por HTTP, con las reglas que no están en el esquema. */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogoCrudHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("catalogo-crud");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private RequestMappingHandlerMapping mapeos;

    private ClienteHttpDePrueba duena;

    @BeforeEach
    void entrarComoDuena() {
        if (usuarioRepository.count() == 0) {
            Usuario alejandra = new Usuario();
            alejandra.setNombre("Alejandra");
            alejandra.setRol(Rol.DUENA);
            alejandra.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            alejandra.setActivo(true);
            alejandra.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(alejandra);
        }
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    /**
     * Nada se borra. Y no es que el endpoint de borrado devuelva un error: es que no
     * existe. Un producto borrado se llevaría consigo la historia de ventas que lo
     * referencia, y las FK son RESTRICT precisamente para que eso no pueda pasar — pero
     * lo que no se ofrece no hay que defenderlo.
     */
    @Test
    void noExisteNingunEndpointDeBorradoEnTodaLaApi() {
        var conDelete = mapeos.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getMethodsCondition().getMethods().stream()
                        .anyMatch(m -> m.name().equals("DELETE")))
                .map(e -> e.getKey().toString())
                .toList();

        System.out.println("VERIFICACION endpoints con verbo DELETE => "
                + (conDelete.isEmpty() ? "ninguno" : conDelete));
        assertThat(conDelete).isEmpty();
    }

    @Test
    void elCicloCompletoDeUnaVariante() {
        long marcaId = idDe(duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"Maybelline\"}"));
        long categoriaId = idDe(duena.post("/api/v1/catalogo/categorias", "{\"nombre\":\"Labios\"}"));

        Respuesta producto = duena.post("/api/v1/catalogo/productos",
                "{\"nombre\":\"Labial mate\",\"marcaId\":" + marcaId
                        + ",\"categoriaId\":" + categoriaId + ",\"descripcion\":\"larga duración\"}");
        System.out.println("VERIFICACION crear producto => " + producto.estado() + " " + producto.cuerpo());
        assertThat(producto.estado()).isEqualTo(201);
        long productoId = idDe(producto);

        Respuesta variante = duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Rojo\",\"tamano\":\"5 ml\","
                        + "\"precioVenta\":38900,\"stockMinimo\":3}");
        System.out.println("VERIFICACION crear variante => " + variante.estado() + " " + variante.cuerpo());
        assertThat(variante.estado()).isEqualTo(201);
        assertThat(variante.cuerpo()).contains("\"stock\":0").doesNotContain("costo");

        long varianteId = idDe(variante);
        Respuesta edicion = duena.put("/api/v1/catalogo/variantes/" + varianteId,
                "{\"productoId\":" + productoId + ",\"tono\":\"Rojo intenso\",\"tamano\":\"5 ml\","
                        + "\"precioVenta\":42900,\"stockMinimo\":5}");
        System.out.println("VERIFICACION editar precio => " + edicion.estado() + " " + edicion.cuerpo());
        assertThat(edicion.estado()).isEqualTo(200);
        assertThat(edicion.cuerpo()).contains("42900").contains("Rojo intenso");
    }

    @Test
    void unaVarianteActivaNoPuedeTenerPrecioCero() {
        long productoId = crearProductoSuelto();

        Respuesta respuesta = duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Gratis\",\"precioVenta\":0}");

        System.out.println("VERIFICACION variante activa con precio 0 => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("se vendería gratis");
    }

    /**
     * Desactivar con existencias se permite y se advierte. "Esto ya no lo vendo" es una
     * decisión legítima con unidades en el mostrador; lo que no puede es pasar en
     * silencio, porque esas unidades siguen contando en el valor del inventario.
     */
    @Test
    void desactivarUnaVarianteConStockAvisaDelStockQueQueda() {
        long productoId = crearProductoSuelto();
        long varianteId = idDe(duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Con stock\",\"precioVenta\":30000}"));

        duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + varianteId + ",\"cantidad\":7,\"costoUnitario\":12000}]}");

        Respuesta desactivacion = duena.post("/api/v1/catalogo/variantes/" + varianteId + "/desactivacion");

        System.out.println("VERIFICACION desactivar con 7 unidades => " + desactivacion.cuerpo());
        assertThat(desactivacion.estado()).isEqualTo(200);
        assertThat(desactivacion.cuerpo()).contains("\"activo\":false");
        assertThat(desactivacion.cuerpo()).contains("7 unidad(es)");
        assertThat(desactivacion.cuerpo()).contains("registrar la merma");
    }

    @Test
    void desactivarUnaVarianteSinStockNoAdvierteNada() {
        long productoId = crearProductoSuelto();
        long varianteId = idDe(duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Sin stock\",\"precioVenta\":30000}"));

        Respuesta desactivacion = duena.post("/api/v1/catalogo/variantes/" + varianteId + "/desactivacion");

        System.out.println("VERIFICACION desactivar sin existencias => " + desactivacion.cuerpo());
        assertThat(desactivacion.cuerpo()).contains("\"advertencia\":null");
    }

    @Test
    void reactivarConPrecioCeroNoSePermite() {
        long productoId = crearProductoSuelto();
        long varianteId = idDe(duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Para bajar a cero\",\"precioVenta\":30000}"));

        duena.post("/api/v1/catalogo/variantes/" + varianteId + "/desactivacion");
        // Inactiva ya puede quedar en 0.
        Respuesta aCero = duena.put("/api/v1/catalogo/variantes/" + varianteId,
                "{\"productoId\":" + productoId + ",\"tono\":\"Para bajar a cero\",\"precioVenta\":0}");
        assertThat(aCero.estado()).isEqualTo(200);

        Respuesta reactivacion = duena.post("/api/v1/catalogo/variantes/" + varianteId + "/reactivacion");
        System.out.println("VERIFICACION reactivar con precio 0 => " + reactivacion.estado()
                + " " + reactivacion.cuerpo());
        assertThat(reactivacion.estado()).isEqualTo(400);
    }

    @Test
    void desactivarUnProductoConVariantesActivasAvisa() {
        long productoId = crearProductoSuelto();
        duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Activa\",\"precioVenta\":30000}");

        Respuesta desactivacion = duena.post("/api/v1/catalogo/productos/" + productoId + "/desactivacion");

        System.out.println("VERIFICACION desactivar producto con variantes activas => "
                + desactivacion.cuerpo());
        assertThat(desactivacion.cuerpo()).contains("variante(s) activa(s)");
    }

    private long crearProductoSuelto() {
        String sufijo = String.valueOf(System.nanoTime());
        long marcaId = idDe(duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"Marca " + sufijo + "\"}"));
        long categoriaId = idDe(duena.post("/api/v1/catalogo/categorias",
                "{\"nombre\":\"Categoría " + sufijo + "\"}"));
        return idDe(duena.post("/api/v1/catalogo/productos",
                "{\"nombre\":\"Producto " + sufijo + "\",\"marcaId\":" + marcaId
                        + ",\"categoriaId\":" + categoriaId + "}"));
    }

    private long idDe(Respuesta respuesta) {
        assertThat(respuesta.estado())
                .withFailMessage("Esperaba una creación correcta, llegó %d: %s",
                        respuesta.estado(), respuesta.cuerpo())
                .isIn(200, 201);
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(respuesta.cuerpo());
        assertThat(buscador.find()).isTrue();
        return Long.parseLong(buscador.group(1));
    }
}
