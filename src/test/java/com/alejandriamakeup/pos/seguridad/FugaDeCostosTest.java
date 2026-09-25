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

    /**
     * Banderas que nombran el costo sin publicar ninguno.
     *
     * <p>{@code sinCosto} es un booleano derivado de {@code costoPromedio == 0}: dice
     * que por esa variante nunca entró mercancía valorada, no cuánto costó. El punto de
     * venta lo necesita para rechazar la variante <em>al agregarla al carrito</em>, que
     * es donde el rechazo no cuesta nada; sin él, el único momento de enterarse sería el
     * 409 del cobro, con el carrito lleno y una clienta enfrente.
     *
     * <p>Se descuenta del barrido de <strong>palabras</strong> y solo con su valor
     * pegado, de modo que un campo llamado {@code sinCostoPromedio} o
     * {@code "sinCosto":777771} seguiría cayendo. El barrido de <strong>valores</strong>
     * no se toca y corre sobre el cuerpo entero: si algún día esta bandera arrastrara un
     * importe, el test lo vería igual.
     */
    private static final List<String> BANDERAS_SIN_IMPORTE = List.of(
            "\"sinCosto\":true", "\"sinCosto\":false");

    /**
     * La única ruta que este barrido no mira, nombrada una por una y a propósito.
     *
     * <p>Devuelve los bytes de un PDF. Leídos como texto son basura comprimida, así que
     * buscar "costo" ahí no probaría nada y además haría el test no determinista: una
     * coincidencia por azar en el flujo comprimido lo pondría en rojo un día cualquiera.
     *
     * <p><strong>Se excluye la ruta, no el tipo de contenido.</strong> Excluir todo lo
     * que sea {@code application/pdf} dejaría sin auditar cualquier endpoint futuro que
     * devuelva un PDF, sin que nadie lo haya decidido. Así, el que agregue el siguiente
     * tiene que venir aquí a escribirlo, y al escribirlo se pregunta dónde lo audita.
     *
     * <p>El contenido de este PDF sí se audita, sobre el texto extraído y no sobre los
     * bytes: {@code ReciboSinCostosTest}.
     */
    private static final List<String> AUDITADAS_EN_OTRO_SITIO = List.of(
            "/api/v1/ventas/{id}/recibo");

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
    private static Long idVenta;
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

        // Una venta de verdad, con COSTO_UNO congelado dentro de su venta_item. Sin
        // ella, GET /api/v1/ventas/{id} respondería 404 en el barrido y la ruta
        // pasaría el test sin haber enseñado nunca un cuerpo.
        if (idVenta == null) {
            duena.post("/api/v1/caja/sesiones", "{}");
            Respuesta venta = duena.post("/api/v1/ventas",
                    // Sin la palabra "costo" en el uuid: viaja en el cuerpo de la
                    // respuesta y el barrido lo leería como una fuga.
                    "{\"uuid\":\"barrido-de-la-fuga\",\"metodoPago\":\"EFECTIVO\","
                            + "\"efectivoRecibido\":50000,\"lineas\":[{\"varianteId\":"
                            + idVariante + ",\"cantidad\":1}]}");
            idVenta = Long.parseLong(venta.cuerpo().replaceFirst("^\\{\"id\":(\\d+).*$", "$1"));
        }
    }

    @Test
    void ningunEndpointDeLecturaMuestraCostosAUnaEmpleada() {
        List<String> rutas = rutasDeLecturaDeLaApi();
        List<String> fugas = new ArrayList<>();

        System.out.println("VERIFICACION barriendo " + rutas.size()
                + " endpoints de lectura con sesión de EMPLEADA (costos " + COSTO_UNO
                + " y " + COSTO_DOS + "), excluida " + AUDITADAS_EN_OTRO_SITIO + ":");

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

            String sinBanderas = cuerpo;
            for (String bandera : BANDERAS_SIN_IMPORTE) {
                sinBanderas = sinBanderas.replace(bandera, "");
            }

            for (String prohibido : PALABRAS_PROHIBIDAS) {
                if (sinBanderas.toLowerCase().contains(prohibido.toLowerCase())) {
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
     * La exclusión tiene que seguir apuntando a una ruta que existe.
     *
     * <p>Si mañana el recibo se sirve desde otra ruta, esta entrada queda muerta: la
     * ruta nueva entraría al barrido —que es el lado seguro— pero la lista de
     * excepciones diría una mentira, y una excepción que ya no excluye nada es
     * exactamente la que nadie vuelve a revisar.
     */
    @Test
    void laRutaExcluidaDelBarridoSigueExistiendo() {
        List<String> mapeadas = new ArrayList<>();
        mapeos.getHandlerMethods().forEach((info, handler) -> {
            var patrones = info.getPathPatternsCondition();
            if (patrones != null) {
                patrones.getPatterns().forEach(p -> mapeadas.add(p.getPatternString()));
            }
        });

        System.out.println("VERIFICACION exclusiones del barrido => " + AUDITADAS_EN_OTRO_SITIO);
        assertThat(mapeadas)
                .withFailMessage("La lista de rutas excluidas del barrido nombra algo que ya "
                        + "no está mapeado: %s. Hay que borrar la entrada o corregirla.",
                        AUDITADAS_EN_OTRO_SITIO)
                .containsAll(AUDITADAS_EN_OTRO_SITIO);
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

    /**
     * Y el catálogo del punto de venta no los trae ni siquiera para la dueña.
     *
     * <p>Lo único que menciona el costo ahí es la bandera {@code sinCosto}, y se
     * comprueba en los dos sentidos: que está —el punto de venta la necesita para
     * rechazar al agregar al carrito— y que no arrastra ningún importe.
     */
    @Test
    void elCatalogoDelPuntoDeVentaNoTraeCostosParaNadie() {
        Respuesta respuesta = duena.get("/api/v1/catalogo");
        String sinBanderas = respuesta.cuerpo()
                .replace("\"sinCosto\":true", "").replace("\"sinCosto\":false", "");

        System.out.println("VERIFICACION DUENA lee el catálogo del POS => sin importes: "
                + !sinBanderas.toLowerCase().contains("costo"));
        assertThat(respuesta.cuerpo()).contains("\"sinCosto\":");
        assertThat(sinBanderas.toLowerCase()).doesNotContain("costo");
        assertThat(respuesta.cuerpo())
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
                if (ruta.startsWith("/api/") && !AUDITADAS_EN_OTRO_SITIO.contains(ruta)) {
                    rutas.add(ruta.replaceAll("\\{[^/}]+}", String.valueOf(idPara(ruta))));
                }
            }
        });
        return rutas.stream().sorted().toList();
    }

    /**
     * Qué id sustituir en cada ruta con variable.
     *
     * <p>El id de la variante sirve para casi todo por casualidad —los fixtures son
     * pequeños y los ids se solapan—, pero no para {@code /ventas/{id}}: ahí un id
     * equivocado da 404 y el barrido pasaría sin haber mirado nunca el cuerpo de una
     * venta, que es justamente donde vive el costo congelado.
     */
    private long idPara(String ruta) {
        return ruta.startsWith("/api/v1/ventas") ? idVenta : idVariante;
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
