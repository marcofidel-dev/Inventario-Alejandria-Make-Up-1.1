package com.alejandriamakeup.pos.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

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
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.metricas.dto.MetricasDto;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.VentasPorMetodo;

/**
 * Las métricas, contra un conjunto que incluye <strong>una venta anulada y una con
 * descuento</strong>, con los valores esperados calculados a mano y escritos
 * literales.
 *
 * <p>Los literales son el punto entero del test. Si el esperado se calculara aquí
 * con las mismas sumas que hace el servicio, el test pasaría también con el servicio
 * roto: estaría comparando una fórmula consigo misma. Escritos a mano, cualquier
 * desviación salta.
 *
 * <h2>Por qué justamente una anulada y una con descuento</h2>
 *
 * Son los dos filtros que se olvidan, y se olvidan porque olvidarlos no rompe nada:
 * devuelven un número más alto y perfectamente creíble. Sobre este mismo conjunto,
 * el margen correcto del periodo es <strong>40%</strong>; ignorando el descuento
 * sale 43%, contando la venta anulada sale 51%. Ninguno de los tres se ve mal en una
 * pantalla, y por eso nadie los revisa.
 *
 * <p>El fixture está construido además para que <strong>los dos rankings salgan al
 * revés</strong>: el delineador barato encabeza por unidades (8 contra 2) y la base
 * cara por margen aportado (64.000 contra 13.000). Con un fixture donde coincidieran,
 * un servicio que devolviera dos veces la misma lista pasaría el test.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricasHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("metricas");

    // --- El catálogo del fixture, con precios y costos elegidos a mano -------------
    private static final long PRECIO_DELINEADOR = 10_000;
    private static final long COSTO_DELINEADOR = 8_000;
    private static final long PRECIO_BASE = 60_000;
    private static final long COSTO_BASE = 25_000;
    private static final long COSTO_MENOR = 1_000;

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
    private ServicioMetricas servicioMetricas;

    @Autowired
    private com.alejandriamakeup.pos.catalogo.VarianteRepository varianteRepository;

    private static Long idDelineador;
    private static Long idBase;
    private static Long idVenceAyer;
    private static Long idVenceHoy;
    private static Long idVenceEn45;
    private static Long idVenceEn200;
    private static Long idNuncaRecibida;

    private ClienteHttpDePrueba duena;
    private ClienteHttpDePrueba empleada;

    @BeforeEach
    void sembrarYEntrar() {
        if (idDelineador == null) {
            sembrar();
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
    }

    // ------------------------------------------------------------------ acceso

    @Test
    void elModuloEnteroLeDa403ALaEmpleada() {
        List<String> rutas = List.of(
                "/api/v1/metricas/panel",
                "/api/v1/metricas/sin-rotacion",
                "/api/v1/metricas/vencimientos");

        System.out.println("VERIFICACION métricas con sesión de EMPLEADA:");
        for (String ruta : rutas) {
            Respuesta respuesta = empleada.get(ruta);
            System.out.println("    " + respuesta.estado() + "  " + ruta);

            assertThat(respuesta.estado())
                    .withFailMessage("%s le respondió %d a una EMPLEADA. El módulo entero es de "
                            + "la DUENA: son costos y márgenes de principio a fin.",
                            ruta, respuesta.estado())
                    .isEqualTo(403);
            assertThat(respuesta.cuerpo()).contains("\"codigo\":\"SIN_PERMISO\"");
        }

        // Y a la DUENA sí, para que el 403 de arriba no pase por estar todo roto.
        for (String ruta : rutas) {
            assertThat(duena.get(ruta).estado()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ panel

    @Test
    void elResumenDelDiaIgnoraLaAnuladaYRestaElDescuento() {
        MetricasDto.Resumen actual = panelDeHoy().actual();

        System.out.println("VERIFICACION resumen de hoy => " + actual);
        System.out.println("    correcto 40% | olvidando el descuento 43% | "
                + "contando la anulada 51%");

        // Dos ventas COMPLETADAS: 5+1 unidades una, 3+1 la otra. La anulada (4+2) no
        // existe para nadie.
        assertThat(actual.ventas()).isEqualTo(2);
        assertThat(actual.unidades()).isEqualTo(10);

        // 110.000 la limpia + 81.000 la de descuento (90.000 - 9.000).
        assertThat(actual.ingreso()).isEqualTo(191_000);

        // 8 delineadores a 8.000 + 2 bases a 25.000.
        assertThat(actual.costo()).isEqualTo(114_000);

        assertThat(actual.margen()).isEqualTo(77_000);
        assertThat(actual.margenPorcentaje()).isEqualTo(40);
    }

    @Test
    void elPeriodoAnteriorSinVentasNoInventaUnPorcentaje() {
        MetricasDto.Resumen anterior = panelDeHoy().anterior();

        System.out.println("VERIFICACION ayer (sin ventas) => " + anterior);

        assertThat(anterior.ventas()).isZero();
        assertThat(anterior.ingreso()).isZero();
        assertThat(anterior.margen()).isZero();

        // null y no 0: "no hubo de qué sacar porcentaje" no es "el margen fue nulo".
        assertThat(anterior.margenPorcentaje()).isNull();
    }

    @Test
    void elDesgloseSeparaLosMetodosYDejaAfueraLaAnulada() {
        List<VentasPorMetodo> desglose = panelDeHoy().porMetodoPago();

        System.out.println("VERIFICACION por método de pago => " + desglose);

        // La anulada también fue en EFECTIVO: si se colara, el efectivo diría 200.000.
        assertThat(desglose).containsExactly(
                new VentasPorMetodo(MetodoPago.EFECTIVO, 1, 110_000),
                new VentasPorMetodo(MetodoPago.TARJETA, 1, 81_000));
    }

    @Test
    void losDosRankingsNoSonLaMismaLista() {
        MetricasDto.Panel panel = panelDeHoy();

        System.out.println("VERIFICACION por unidades => " + panel.masVendidosPorUnidades());
        System.out.println("VERIFICACION por margen   => " + panel.masVendidosPorMargen());

        MetricasDto.ProductoVendido delineador = panel.masVendidosPorUnidades().get(0);
        assertThat(delineador.varianteId()).isEqualTo(idDelineador);
        assertThat(delineador.unidades()).isEqualTo(8);
        assertThat(delineador.ingreso()).isEqualTo(77_000);   // 50.000 + (30.000 - 3.000)
        assertThat(delineador.costo()).isEqualTo(64_000);     // 8 x 8.000
        assertThat(delineador.margen()).isEqualTo(13_000);
        assertThat(delineador.margenPorcentaje()).isEqualTo(17);

        MetricasDto.ProductoVendido base = panel.masVendidosPorMargen().get(0);
        assertThat(base.varianteId()).isEqualTo(idBase);
        assertThat(base.unidades()).isEqualTo(2);
        assertThat(base.ingreso()).isEqualTo(114_000);        // 60.000 + (60.000 - 6.000)
        assertThat(base.costo()).isEqualTo(50_000);           // 2 x 25.000
        assertThat(base.margen()).isEqualTo(64_000);
        assertThat(base.margenPorcentaje()).isEqualTo(56);

        // Lo que más sale no es lo que más deja: si el servicio devolviera dos veces la
        // misma lista, esto es lo que caería.
        assertThat(panel.masVendidosPorUnidades().get(0).varianteId())
                .isNotEqualTo(panel.masVendidosPorMargen().get(0).varianteId());
    }

    @Test
    void elInventarioSeValoraACostoPromedio() {
        MetricasDto.Inventario inventario = panelDeHoy().inventario();

        System.out.println("VERIFICACION inventario => " + inventario.valorACosto()
                + " en " + inventario.unidades() + " unidades");

        // 92 delineadores x 8.000 + 48 bases x 25.000 + 4 variantes con 10 x 1.000.
        assertThat(inventario.valorACosto()).isEqualTo(1_976_000);
        assertThat(inventario.unidades()).isEqualTo(180);

        // Dos bajo mínimo, y de las dos clases: una activa con 10 unidades contra un
        // mínimo de 20, y una que nunca se recibió, con stock 0 contra un mínimo de 3.
        assertThat(inventario.variantesBajoMinimo())
                .extracting(MetricasDto.StockBajo::varianteId)
                .containsExactly(idVenceAyer, idNuncaRecibida);
        assertThat(inventario.variantesBajoMinimo().get(1).stock()).isZero();
    }

    /**
     * "Stock bajo" está escrito dos veces: en SQL, en
     * {@code VarianteRepository.bajoMinimo()}, y en Java, dentro del panel. Se aceptó
     * la duplicación a sabiendas —consultarla otra vez costaría una consulta más y un
     * N+1 al pedir las descripciones—, así que el precio a pagar es este test.
     *
     * <p>Compara los <strong>conjuntos completos</strong> y no solo el tamaño: dos
     * listas de dos elementos pueden tener elementos distintos, y una comprobación de
     * cardinalidad lo dejaría pasar. Es la misma forma de {@code CoherenciaSinCostoTest}.
     */
    @Test
    void elStockBajoDelPanelEsElMismoConjuntoQueElDelRepositorio() {
        List<Long> segunElPanel = panelDeHoy().inventario().variantesBajoMinimo().stream()
                .map(MetricasDto.StockBajo::varianteId)
                .sorted()
                .toList();
        List<Long> segunElSql = varianteRepository.bajoMinimo().stream()
                .map(Variante::getId)
                .sorted()
                .toList();

        System.out.println("VERIFICACION stock bajo => panel " + segunElPanel
                + " | SQL " + segunElSql);

        assertThat(segunElPanel)
                .withFailMessage("El panel dice %s y VarianteRepository.bajoMinimo() dice %s. "
                        + "Son la misma regla escrita dos veces y se separaron.",
                        segunElPanel, segunElSql)
                .isEqualTo(segunElSql);
        assertThat(segunElPanel).isNotEmpty();
    }

    // ------------------------------------------------------------------ vencimientos

    @Test
    void loQueVenceHoyTodaviaSePuedeVender() {
        MetricasDto.Vencimientos conteo = panelDeHoy().vencimientos();
        List<MetricasDto.Vencimiento> detalle = servicioMetricas.vencimientos();

        System.out.println("VERIFICACION vencimientos => " + conteo);
        detalle.forEach(fila -> System.out.println("    " + fila.fechaVencimiento()
                + "  " + fila.diasParaVencer() + " días  " + fila.descripcion()));

        // Este es el test de la comparación contra fecha y no contra instante: con un
        // datetime('now'), lo que vence hoy caería en "vencidos" a partir de las
        // 00:00:01, y todavía se puede vender.
        assertThat(conteo.vencidos()).isEqualTo(1);
        assertThat(conteo.hasta30()).isEqualTo(1);
        assertThat(conteo.entre31y60()).isEqualTo(1);
        assertThat(conteo.entre61y90()).isZero();

        assertThat(detalle)
                .extracting(MetricasDto.Vencimiento::varianteId)
                .containsExactly(idVenceAyer, idVenceHoy, idVenceEn45);

        assertThat(detalle.get(0).diasParaVencer()).isEqualTo(-1);
        assertThat(detalle.get(1).diasParaVencer()).isZero();
        assertThat(detalle.get(2).diasParaVencer()).isEqualTo(45);

        // Lo que se pierde si vence sin venderse: 10 unidades a 1.000.
        assertThat(detalle.get(0).valorACosto()).isEqualTo(10_000);

        // La que vence en 200 días queda fuera del horizonte, y la vencida sin stock
        // tampoco aparece: no queda nada que botar.
        assertThat(detalle)
                .extracting(MetricasDto.Vencimiento::varianteId)
                .doesNotContain(idVenceEn200, idNuncaRecibida);
    }

    // ------------------------------------------------------------------ rotación

    @Test
    void sinRotacionListaLoQueTieneStockYNoSeVendio() {
        MetricasDto.SinRotacion respuesta = servicioMetricas.sinRotacion(90);

        System.out.println("VERIFICACION sin rotación (90 días) => "
                + respuesta.filas().size() + " variantes");
        System.out.println("    " + respuesta.aclaracion());

        assertThat(respuesta.filas())
                .extracting(MetricasDto.VarianteQuieta::varianteId)
                .containsExactlyInAnyOrder(idVenceAyer, idVenceHoy, idVenceEn45, idVenceEn200);

        // El delineador y la base se vendieron hoy; la nunca recibida no tiene stock.
        assertThat(respuesta.filas())
                .extracting(MetricasDto.VarianteQuieta::varianteId)
                .doesNotContain(idDelineador, idBase, idNuncaRecibida);

        assertThat(respuesta.filas().get(0).valorACosto()).isEqualTo(10_000);

        // "Sin ventas en N días" no es "nunca vendido", y la respuesta lo dice: en la
        // pantalla la distinción importa, porque un producto que se vendía y dejó de
        // venderse es un problema distinto a uno que nunca se vendió.
        assertThat(respuesta.dias()).isEqualTo(90);
        assertThat(respuesta.aclaracion()).contains("no dice si se vendieron antes");
    }

    // ------------------------------------------------------------------ tope de rango

    @Test
    void unRangoMayorAUnAnoSeRechaza() {
        Respuesta demasiado = duena.get("/api/v1/metricas/sin-rotacion?dias=400");
        Respuesta justo = duena.get("/api/v1/metricas/sin-rotacion?dias=366");

        System.out.println("VERIFICACION dias=400 => " + demasiado.estado() + " "
                + demasiado.cuerpo());

        assertThat(demasiado.estado()).isEqualTo(400);
        assertThat(demasiado.cuerpo()).contains("\"codigo\":\"PETICION_INVALIDA\"");
        assertThat(demasiado.cuerpo()).contains("366");
        assertThat(justo.estado()).isEqualTo(200);

        assertThat(duena.get("/api/v1/metricas/sin-rotacion?dias=0").estado()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ el HTTP de verdad

    @Test
    void elPanelViajaEnteroEnUnaSolaRespuesta() {
        Respuesta respuesta = duena.get("/api/v1/metricas/panel?periodo=DIA");
        String cuerpo = respuesta.cuerpo();

        System.out.println("VERIFICACION cuerpo del panel => " + cuerpo);

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(cuerpo).contains("\"ingreso\":191000");
        assertThat(cuerpo).contains("\"margen\":77000");

        // La agrupación va en la respuesta y no en un comentario: la pantalla tiene que
        // poder decir contra qué se está comparando.
        assertThat(cuerpo).contains("\"agrupacion\":\"FECHA_DE_VENTA\"");

        // Todo el panel en una llamada, no nueve.
        assertThat(cuerpo).contains("\"porMetodoPago\"")
                .contains("\"masVendidosPorUnidades\"")
                .contains("\"masVendidosPorMargen\"")
                .contains("\"inventario\"")
                .contains("\"vencimientos\"");
    }

    // ------------------------------------------------------------------ fixture

    private MetricasDto.Panel panelDeHoy() {
        return servicioMetricas.panel(Periodo.DIA, LocalDate.now());
    }

    private void sembrar() {
        long idDuena = crear("Alejandra", Rol.DUENA, "1111");
        crear("Camila", Rol.EMPLEADA, "2222");

        Marca marca = servicioMarca.crear("Maybelline");
        Categoria categoria = servicioCategoria.crear("Rostro");
        Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Surtido", marca.getId(), categoria.getId(), null));

        LocalDate hoy = LocalDate.now();

        idDelineador = variante(producto, "Delineador", PRECIO_DELINEADOR, 0, null);
        idBase = variante(producto, "Base", PRECIO_BASE, 0, null);
        idVenceAyer = variante(producto, "Vence ayer", 5_000, 20, hoy.minusDays(1));
        idVenceHoy = variante(producto, "Vence hoy", 5_000, 0, hoy);
        idVenceEn45 = variante(producto, "Vence en 45", 5_000, 0, hoy.plusDays(45));
        idVenceEn200 = variante(producto, "Vence en 200", 5_000, 0, hoy.plusDays(200));

        // Sin carga inicial: nunca entró mercancía. Stock 0 contra un mínimo de 3, que
        // es exactamente lo que "bajo mínimo" tiene que atrapar de un producto que hay
        // que comprar por primera vez. Y vencida, pero sin nada que botar.
        idNuncaRecibida = variante(producto, "Nunca recibida", 5_000, 3, hoy.minusDays(10));

        servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(idDelineador, 100, COSTO_DELINEADOR),
                new PeticionesInventario.CargaInicial.Linea(idBase, 50, COSTO_BASE),
                new PeticionesInventario.CargaInicial.Linea(idVenceAyer, 10, COSTO_MENOR),
                new PeticionesInventario.CargaInicial.Linea(idVenceHoy, 10, COSTO_MENOR),
                new PeticionesInventario.CargaInicial.Linea(idVenceEn45, 10, COSTO_MENOR),
                new PeticionesInventario.CargaInicial.Linea(idVenceEn200, 10, COSTO_MENOR))),
                idDuena);

        ClienteHttpDePrueba cajera = new ClienteHttpDePrueba(puerto);
        cajera.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        cajera.post("/api/v1/caja/sesiones", "{}");

        // Limpia: 5 delineadores + 1 base = 110.000, en efectivo.
        cajera.post("/api/v1/ventas", venta("limpia", "EFECTIVO", null,
                idDelineador, 5, idBase, 1));

        // Con descuento: 30.000 + 60.000 = 90.000, menos 9.000 = 81.000. El prorrateo
        // reparte 3.000 y 6.000, exacto, para que el esperado no dependa del residuo.
        cajera.post("/api/v1/ventas", venta("con-descuento", "TARJETA", 9_000L,
                idDelineador, 3, idBase, 1));

        // Anulada, y en efectivo como la limpia: si se colara en el desglose, el
        // efectivo saldría 200.000 en vez de 110.000.
        Respuesta anulable = cajera.post("/api/v1/ventas", venta("anulada", "EFECTIVO", null,
                idDelineador, 4, idBase, 2));
        long idAnulable = Long.parseLong(anulable.cuerpo().replaceFirst("^\\{\"id\":(\\d+).*$", "$1"));
        cajera.post("/api/v1/ventas/" + idAnulable + "/anulacion",
                "{\"motivo\":\"la clienta se arrepintió\"}");
    }

    private String venta(String uuid, String metodo, Long descuento,
                         long primera, int cantidadPrimera, long segunda, int cantidadSegunda) {
        return "{\"uuid\":\"" + uuid + "\",\"metodoPago\":\"" + metodo + "\""
                + (descuento == null ? "" : ",\"descuento\":" + descuento)
                + ",\"efectivoRecibido\":500000"
                + ",\"lineas\":[{\"varianteId\":" + primera + ",\"cantidad\":" + cantidadPrimera
                + "},{\"varianteId\":" + segunda + ",\"cantidad\":" + cantidadSegunda + "}]}";
    }

    private Long variante(Producto producto, String tono, long precio, int stockMinimo,
                          LocalDate vencimiento) {
        Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), tono, "1 un", null, precio, stockMinimo, vencimiento, null));
        return variante.getId();
    }

    private long crear(String nombre, Rol rol, String pin) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setPinHash(new BCryptPasswordEncoder().encode(pin));
        usuario.setRol(rol);
        usuario.setActivo(true);
        usuario.setFechaCreacion(com.alejandriamakeup.pos.config.Fechas.ahora());
        return usuarioRepository.save(usuario).getId();
    }
}
