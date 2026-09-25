package com.alejandriamakeup.pos.ventas.recibo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaRepository;

/**
 * El recibo de punta a punta: se genera al cobrar, se entrega, se regenera y no
 * depende del catálogo.
 *
 * <p>Con raíz de archivos propia — {@code app.paths.raiz} apuntando a una carpeta de
 * esta clase — porque el perfil de test comparte una sola carpeta entre todas las
 * suites y los consecutivos empiezan en V-000001 en cada base aislada. Sin esto, dos
 * clases escribirían el mismo {@code recibos/2026/08/V-000001.pdf} y una leería el
 * papel de la otra.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class ReciboDeVentaHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("recibo-http");
    private static final Path RAIZ =
            Path.of(System.getProperty("java.io.tmpdir"), "AlejandriaMakeUp-test-recibos");

    /** Irrepetible: si aparece en el papel, salió del costo congelado. */
    private static final long COSTO_LABIAL = 777_771;
    private static final long PRECIO_LABIAL = 38_900;

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
        registro.add("app.paths.raiz", () -> RAIZ.toString());
    }

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private VentaRepository ventaRepository;
    @Autowired private ServicioRecibo servicioRecibo;

    private static Long idMarca;
    private static Long idCategoria;
    private static Long idProducto;
    private static Long idLabial;
    private static Long idVenta;
    private static String consecutivo;

    private ClienteHttpDePrueba duena;
    private ClienteHttpDePrueba empleada;

    @BeforeEach
    void sembrarYEntrar() {
        if (idLabial == null) {
            long idDuena = crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            idMarca = marca.getId();
            idCategoria = categoria.getId();
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate Superstay", marca.getId(), categoria.getId(), null));
            idProducto = producto.getId();

            Variante labial = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo carmín", "5 ml", null, PRECIO_LABIAL, 2, null, null));
            idLabial = labial.getId();

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(idLabial, 40, COSTO_LABIAL))),
                    idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
    }

    // -------------------------------------------------------- 1. se genera solo

    /**
     * Cobrar deja el recibo escrito, y la respuesta del cobro ya trae su ruta.
     *
     * <p>La ruta es <strong>relativa</strong> y particionada por año y mes. Relativa
     * porque la carpeta de datos se mueve con el equipo: una ruta absoluta convertiría
     * un cambio de letra de unidad en cinco años de recibos ilocalizables.
     */
    @Test
    @Order(1)
    void alCobrarSeGeneraElReciboYLaRutaGuardadaEsRelativa() {
        duena.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"Alejandria Make Up\",\"nit\":\"1.234.567.890-1\","
                        + "\"direccion\":\"Cra 10 # 15-30, Puerto Gaitán\","
                        + "\"telefono\":\"300 123 4567\",\"pieRecibo\":\"Gracias por su compra\"}");
        duena.post("/api/v1/caja/sesiones", "{}");

        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":100000,\"lineas\":[{\"varianteId\":" + idLabial
                        + ",\"cantidad\":2}]}");

        assertThat(respuesta.estado()).isEqualTo(201);

        Venta venta = ventaRepository.findAll().get(0);
        idVenta = venta.getId();
        consecutivo = venta.getConsecutivo();
        Path archivo = RAIZ.resolve(venta.getRutaRecibo());

        System.out.println("VERIFICACION ruta guardada => " + venta.getRutaRecibo()
                + " | archivo existe: " + Files.isRegularFile(archivo));

        // La respuesta del cobro ya la trae: la pantalla puede ofrecer "ver recibo" sin
        // otra llamada, que es el momento en que la clienta lo está esperando.
        assertThat(respuesta.cuerpo()).contains("\"rutaRecibo\":\"recibos/");

        assertThat(venta.getRutaRecibo())
                .startsWith("recibos/")
                .endsWith("/" + consecutivo + ".pdf")
                .matches("recibos/\\d{4}/\\d{2}/.+\\.pdf");
        assertThat(Path.of(venta.getRutaRecibo()).isAbsolute()).isFalse();
        assertThat(Files.isRegularFile(archivo)).isTrue();
    }

    // ------------------------------------------------------------ 2. se entrega

    @Test
    @Order(2)
    void elEndpointEntregaElPdf() {
        Respuesta respuesta = duena.get("/api/v1/ventas/" + idVenta + "/recibo");

        System.out.println("VERIFICACION GET recibo => " + respuesta.estado() + " "
                + respuesta.cabecera("Content-Type").orElse("(sin tipo)") + " "
                + respuesta.cabecera("Content-Disposition").orElse(""));

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cabecera("Content-Type")).hasValue("application/pdf");
        assertThat(respuesta.cabecera("Content-Disposition"))
                .hasValue("inline; filename=\"" + consecutivo + ".pdf\"");
    }

    /**
     * <strong>El PDF entregado no contiene ningún costo.</strong> Aquí es donde se
     * audita lo que {@code FugaDeCostosTest} no puede barrer: su barrido lee los cuerpos
     * como texto y los bytes de un PDF comprimido no lo son. Esto lee el texto extraído,
     * que es lo que de verdad le llega a la clienta.
     */
    @Test
    @Order(3)
    void elPdfEntregadoNoContieneNingunCosto() throws IOException {
        String texto = textoDelPdf(servicioRecibo.pdf(idVenta));

        System.out.println("VERIFICACION texto del PDF entregado:\n" + texto);
        System.out.println("VERIFICACION ¿aparece el costo " + COSTO_LABIAL + "? "
                + texto.contains(String.valueOf(COSTO_LABIAL)));

        assertThat(texto).doesNotContain(String.valueOf(COSTO_LABIAL));
        assertThat(texto).doesNotContain("777.771");
        assertThat(texto.toLowerCase()).doesNotContain("costo").doesNotContain("margen");
        // Y lo que sí tiene que decir.
        assertThat(texto).contains("RECIBO DE VENTA").contains(consecutivo);
    }

    // -------------------------------------- 4. lo impreso sale de la venta

    /**
     * <strong>La razón de ser del campo congelado.</strong> Se genera el recibo, se
     * cambia el nombre del producto en el catálogo, se regenera, y el contenido es
     * idéntico. Sin esto, renombrar un producto reescribiría el comprobante de todo lo
     * que se vendió con el nombre viejo.
     *
     * <p>Se compara el <strong>texto extraído</strong> y no los bytes: PDFBox estampa
     * fecha de creación e identificador de documento, así que dos generaciones nunca dan
     * el mismo archivo aunque digan exactamente lo mismo.
     */
    @Test
    @Order(4)
    void cambiarElNombreDelProductoNoCambiaUnReciboYaEmitido() throws IOException {
        String antes = textoDelPdf(servicioRecibo.pdf(idVenta));

        Respuesta renombrada = duena.put("/api/v1/catalogo/productos/" + idProducto,
                "{\"nombre\":\"OTRO NOMBRE COMPLETAMENTE DISTINTO\",\"marcaId\":" + idMarca
                        + ",\"categoriaId\":" + idCategoria + ",\"descripcion\":null}");
        assertThat(renombrada.estado()).isEqualTo(200);

        // Se fuerza la regeneración borrando la ruta: el endpoint solo regenera si falta.
        Venta venta = ventaRepository.findById(idVenta).orElseThrow();
        venta.setRutaRecibo(null);
        ventaRepository.save(venta);
        assertThat(duena.post("/api/v1/ventas/" + idVenta + "/recibo").estado()).isEqualTo(200);

        String despues = textoDelPdf(servicioRecibo.pdf(idVenta));

        System.out.println("VERIFICACION producto renombrado a «OTRO NOMBRE COMPLETAMENTE "
                + "DISTINTO»; ¿el recibo cambió? " + !antes.equals(despues));

        assertThat(despues)
                .withFailMessage("El recibo cambió al renombrar el producto. Lo impreso tiene "
                        + "que salir de venta_item, no de un join contra el catálogo.%n"
                        + "Antes:%n%s%nDespués:%n%s", antes, despues)
                .isEqualTo(antes);
        assertThat(despues).contains("Labial mate Superstay");
        assertThat(despues).doesNotContain("OTRO NOMBRE COMPLETAMENTE DISTINTO");
    }

    /** Dos generaciones de la misma venta dicen exactamente lo mismo. */
    @Test
    @Order(5)
    void dosGeneracionesDeLaMismaVentaProducenElMismoContenido() throws IOException {
        String primera = textoDelPdf(servicioRecibo.pdf(idVenta));

        Venta venta = ventaRepository.findById(idVenta).orElseThrow();
        venta.setRutaRecibo(null);
        ventaRepository.save(venta);
        servicioRecibo.generarSiFalta(idVenta);

        String segunda = textoDelPdf(servicioRecibo.pdf(idVenta));

        System.out.println("VERIFICACION dos generaciones => "
                + (primera.equals(segunda) ? "idénticas" : "DISTINTAS"));
        assertThat(segunda).isEqualTo(primera);
    }

    // ------------------------------------------------------- 6. recuperación

    /**
     * Una venta sin recibo: el GET no inventa nada, la consulta la encuentra y el POST
     * la recupera.
     */
    @Test
    @Order(6)
    void unaVentaSinReciboSeEncuentraYSeRegenera() {
        Venta venta = ventaRepository.findById(idVenta).orElseThrow();
        venta.setRutaRecibo(null);
        ventaRepository.save(venta);

        Respuesta faltante = duena.get("/api/v1/ventas/" + idVenta + "/recibo");
        Respuesta listado = duena.get("/api/v1/ventas/sin-recibo");

        System.out.println("VERIFICACION GET sin recibo => " + faltante.estado() + " "
                + faltante.cuerpo());
        System.out.println("VERIFICACION sin-recibo => " + listado.cuerpo());

        assertThat(faltante.estado()).isEqualTo(404);
        assertThat(faltante.cuerpo()).contains("no tiene recibo generado");
        assertThat(listado.cuerpo()).contains("\"id\":" + idVenta).contains("\"rutaRecibo\":null");

        Respuesta regenerada = duena.post("/api/v1/ventas/" + idVenta + "/recibo");
        Respuesta yaNoFalta = duena.get("/api/v1/ventas/sin-recibo");

        System.out.println("VERIFICACION tras regenerar => " + regenerada.estado()
                + " | sin-recibo ahora: " + yaNoFalta.cuerpo());

        assertThat(regenerada.estado()).isEqualTo(200);
        assertThat(regenerada.cuerpo()).contains("\"rutaRecibo\":\"recibos/");
        assertThat(yaNoFalta.cuerpo()).doesNotContain("\"id\":" + idVenta);
        assertThat(duena.get("/api/v1/ventas/" + idVenta + "/recibo").estado()).isEqualTo(200);
    }

    /**
     * Regenerar sobre una venta que ya tiene recibo no lo reescribe: no puede ser una
     * puerta para cambiar un comprobante ya entregado.
     */
    @Test
    @Order(7)
    void regenerarUnReciboQueYaExisteDevuelveElMismoYNoLoReescribe() throws IOException {
        Path archivo = RAIZ.resolve(
                ventaRepository.findById(idVenta).orElseThrow().getRutaRecibo());
        long modificadoAntes = Files.getLastModifiedTime(archivo).toMillis();

        Respuesta respuesta = duena.post("/api/v1/ventas/" + idVenta + "/recibo");

        System.out.println("VERIFICACION regenerar existente => " + respuesta.estado()
                + " | archivo tocado: "
                + (Files.getLastModifiedTime(archivo).toMillis() != modificadoAntes));

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(Files.getLastModifiedTime(archivo).toMillis()).isEqualTo(modificadoAntes);
    }

    // -------------------------------------------------------- 8. quién puede

    /**
     * <strong>La EMPLEADA puede ver y regenerar el recibo de su propia venta.</strong>
     * Reimprimir es parte de vender: obligarla a buscar a la dueña para que una clienta
     * se lleve su comprobante haría que el sistema estorbe justo donde tiene que ayudar.
     */
    @Test
    @Order(8)
    void laEmpleadaVeYRegeneraRecibosPeroNoConfiguraLaTienda() {
        Respuesta ver = empleada.get("/api/v1/ventas/" + idVenta + "/recibo");
        Respuesta regenerar = empleada.post("/api/v1/ventas/" + idVenta + "/recibo");
        Respuesta configuracion = empleada.get("/api/v1/configuracion/tienda");
        Respuesta guardar = empleada.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"La tienda de Camila\"}");

        System.out.println("VERIFICACION EMPLEADA => ver " + ver.estado()
                + ", regenerar " + regenerar.estado()
                + ", leer configuración " + configuracion.estado()
                + ", guardar configuración " + guardar.estado());

        assertThat(ver.estado()).isEqualTo(200);
        assertThat(regenerar.estado()).isEqualTo(200);
        assertThat(configuracion.estado()).isEqualTo(403);
        assertThat(guardar.estado()).isEqualTo(403);
    }

    /**
     * <strong>El recibo de una venta anulada no se borra ni se altera.</strong> Es el
     * registro de lo que se le entregó físicamente a la clienta; el estado lo cuenta el
     * listado, que es donde significa algo.
     */
    @Test
    @Order(9)
    void anularLaVentaNoTocaSuRecibo() throws IOException {
        String antes = textoDelPdf(servicioRecibo.pdf(idVenta));

        Respuesta anulacion = duena.post("/api/v1/ventas/" + idVenta + "/anulacion",
                "{\"motivo\":\"La clienta cambió de opinión\"}");
        assertThat(anulacion.estado()).isEqualTo(200);

        Respuesta recibo = duena.get("/api/v1/ventas/" + idVenta + "/recibo");
        String despues = textoDelPdf(servicioRecibo.pdf(idVenta));

        System.out.println("VERIFICACION venta anulada => recibo sigue en " + recibo.estado()
                + " y ¿cambió? " + !antes.equals(despues));

        assertThat(recibo.estado()).isEqualTo(200);
        assertThat(despues).isEqualTo(antes);
        assertThat(despues.toLowerCase()).doesNotContain("anulada");
    }

    /**
     * La carpeta de recibos cuelga de la misma raíz que la de respaldos.
     *
     * <p>Los comprobantes hay que conservarlos cinco años y quien los respalda es la
     * sincronización en nube de la carpeta de datos, no la aplicación. Si algún día
     * alguien moviera los recibos a otro sitio, se quedarían fuera de esa sincronización
     * sin que nada fallara — hasta que hicieran falta.
     */
    @Test
    @Order(10)
    void losRecibosViajanConLosRespaldos() {
        var rutas = com.alejandriamakeup.pos.config.AppPaths.resolver(List.of());

        System.out.println("VERIFICACION recibos => " + rutas.directorioRecibos());
        System.out.println("VERIFICACION backups => " + rutas.directorioBackups());

        assertThat(rutas.directorioRecibos().getParent())
                .withFailMessage("Los recibos tienen que quedar bajo la misma carpeta que los "
                        + "respaldos: es la que se sincroniza con la nube.")
                .isEqualTo(rutas.directorioBackups().getParent())
                .isEqualTo(rutas.directorioRaiz());
    }

    // ------------------------------------------------------------------ apoyo

    private String textoDelPdf(byte[] bytes) throws IOException {
        try (PDDocument documento = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(documento).strip();
        }
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
