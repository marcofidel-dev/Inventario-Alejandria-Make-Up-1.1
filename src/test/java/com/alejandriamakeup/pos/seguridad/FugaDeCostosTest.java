package com.alejandriamakeup.pos.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 * Ningún endpoint le enseña un costo a la EMPLEADA.
 *
 * <p>Mismo mecanismo que el barrido del cierre a ciegas, y por la misma razón: la fuga
 * no va a llegar por los endpoints que estoy mirando hoy. Va a llegar por uno nuevo —
 * un buscador para el mostrador, un listado de inventario bajo mínimo, una pantalla de
 * "más vendidos" — escrito por alguien que no tenía esta regla en la cabeza. Un barrido
 * automático cubre también los endpoints que todavía no existen.
 *
 * <p>Se comprueban <strong>los nombres y los valores</strong>. Los nombres atrapan al
 * DTO que expone {@code costoPromedio}; los valores atrapan al que lo llama {@code c},
 * o lo mete dentro de una descripción, o lo suma en un total. Los costos del fixture
 * están elegidos para no confundirse con precios, ids, fechas ni cantidades.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FugaDeCostosTest {

    private static final String URL = BaseDatosAislada.urlNueva("fuga-de-costos");

    /** Costos irrepetibles: si aparecen en una respuesta, vinieron del costo. */
    private static final long COSTO_UNO = 777_771;
    private static final long COSTO_DOS = 666_662;
    private static final long PRECIO = 38_900;

    private static final List<String> PALABRAS_PROHIBIDAS = List.of(
            "costoPromedio", "costo_promedio", "costoUnitario", "costo_unitario",
            "margen", "margenPorcentaje", "costo");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

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
    private RequestMappingHandlerMapping mapeos;

    private static Long idVariante;
    private ClienteHttpDePrueba empleada;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void sembrarCostosYEntrar() {
        if (idVariante == null) {
            long idDuena = crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));

            Variante una = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, PRECIO, 3, null, null));
            Variante otra = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Nude", "5 ml", null, PRECIO, 3, null, null));

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(una.getId(), 10, COSTO_UNO),
                    new PeticionesInventario.CargaInicial.Linea(otra.getId(), 10, COSTO_DOS))),
                    idDuena);

            idVariante = una.getId();
        }

        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void ningunEndpointDeLecturaMuestraCostosAUnaEmpleada() {
        List<String> rutas = rutasDeLecturaDeLaApi();
        List<String> fugas = new ArrayList<>();

        System.out.println("VERIFICACION barriendo " + rutas.size()
                + " endpoints de lectura con sesión de EMPLEADA (costos " + COSTO_UNO
                + " y " + COSTO_DOS + "):");

        for (String ruta : rutas) {
            Respuesta respuesta = empleada.get(ruta);
            String cuerpo = respuesta.cuerpo() == null ? "" : respuesta.cuerpo();
            System.out.println("    " + respuesta.estado() + "  " + ruta);

            // Un 403 no puede filtrar: el cuerpo es el mensaje de error, y ese sí
            // nombra el permiso -- que se llama VER_COSTOS_Y_MARGENES.
            if (respuesta.estado() == 403) {
                for (String valor : List.of(String.valueOf(COSTO_UNO), String.valueOf(COSTO_DOS))) {
                    if (cuerpo.contains(valor)) {
                        fugas.add(ruta + " (403) expone el valor " + valor);
                    }
                }
                continue;
            }

            for (String prohibido : PALABRAS_PROHIBIDAS) {
                if (cuerpo.toLowerCase().contains(prohibido.toLowerCase())) {
                    fugas.add(ruta + " menciona '" + prohibido + "' => " + recortar(cuerpo));
                }
            }
            for (String valor : List.of(String.valueOf(COSTO_UNO), String.valueOf(COSTO_DOS))) {
                if (cuerpo.contains(valor)) {
                    fugas.add(ruta + " expone el valor " + valor + " => " + recortar(cuerpo));
                }
            }
        }

        assertThat(fugas)
                .withFailMessage("Con sesión de EMPLEADA, estos endpoints muestran costos:%n%s",
                        String.join("\n", fugas))
                .isEmpty();
    }

    /**
     * La contraparte. Sin esto, un sistema que nunca mostrara costos a nadie pasaría el
     * barrido y dejaría a la dueña sin saber cuánto gana.
     */
    @Test
    void laDuenaSiVeCostosYMargenes() {
        Respuesta respuesta = duena.get("/api/v1/catalogo/costos");

        System.out.println("VERIFICACION DUENA pide costos => " + respuesta.estado()
                + " " + recortar(respuesta.cuerpo()));
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"costoPromedio\":" + COSTO_UNO)
                .contains("\"costoPromedio\":" + COSTO_DOS)
                .contains("margen");
    }

    /** Y el catálogo del punto de venta no los trae ni siquiera para la dueña. */
    @Test
    void elCatalogoDelPuntoDeVentaNoTraeCostosParaNadie() {
        Respuesta respuesta = duena.get("/api/v1/catalogo");

        System.out.println("VERIFICACION DUENA lee el catálogo del POS => sin costos: "
                + !respuesta.cuerpo().contains("costo"));
        assertThat(respuesta.cuerpo())
                .doesNotContain("costo")
                .doesNotContain(String.valueOf(COSTO_UNO))
                .doesNotContain(String.valueOf(COSTO_DOS));
    }

    private List<String> rutasDeLecturaDeLaApi() {
        List<String> rutas = new ArrayList<>();
        mapeos.getHandlerMethods().forEach((info, handler) -> {
            var patrones = info.getPathPatternsCondition();
            if (patrones == null) {
                return;
            }
            var metodos = info.getMethodsCondition().getMethods();
            if (!metodos.isEmpty()
                    && metodos.stream().noneMatch(m -> m.name().equals(HttpMethod.GET.name()))) {
                return;
            }
            for (var patron : patrones.getPatterns()) {
                String ruta = patron.getPatternString();
                if (ruta.startsWith("/api/")) {
                    rutas.add(ruta.replaceAll("\\{[^/}]+}", String.valueOf(idVariante)));
                }
            }
        });
        return rutas.stream().sorted().toList();
    }

    private String recortar(String cuerpo) {
        return cuerpo.length() <= 200 ? cuerpo : cuerpo.substring(0, 200) + "...";
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
