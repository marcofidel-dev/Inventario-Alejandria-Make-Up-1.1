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
import com.alejandriamakeup.pos.caja.EstadoSesionCaja;
import com.alejandriamakeup.pos.caja.MovimientoCajaRepository;
import com.alejandriamakeup.pos.caja.SesionCaja;
import com.alejandriamakeup.pos.caja.SesionCajaRepository;
import com.alejandriamakeup.pos.caja.TipoMovimientoCaja;
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
import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El cobro, de punta a punta.
 *
 * <p>Los tests van en orden porque la sesión de caja es estado global y esta clase
 * recorre su ciclo: primero sin caja, después con la caja de ayer sin cerrar, y solo
 * entonces con la caja de hoy abierta. Son los tres mundos en que el punto de venta
 * puede encontrarse un lunes por la mañana.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class VentaHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("venta-http");

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
    @Autowired private MovimientoInventarioRepository movimientoRepository;
    @Autowired private MovimientoCajaRepository movimientoCajaRepository;
    @Autowired private SesionCajaRepository sesionRepository;
    @Autowired private VentaRepository ventaRepository;
    @Autowired private VentaItemRepository itemRepository;

    // Precios y costos irrepetibles: si un número aparece donde no debe, se sabe de
    // dónde salió.
    private static final long PRECIO_LABIAL = 38_900;
    private static final long PRECIO_BASE = 24_500;
    private static final long PRECIO_PALETA = 52_000;
    private static final long COSTO_LABIAL = 20_100;
    private static final long COSTO_BASE = 12_300;
    private static final long COSTO_PALETA = 30_700;

    private static Long labial;
    private static Long base;
    private static Long paleta;
    private static Long sinCosto;
    private static Long idDuena;
    private static Long idSesionDeAyer;

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
            idDuena = usuarioRepository.save(usuaria).getId();

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Rostro");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));

            labial = nuevaVariante(producto, "Rojo", "5 ml", PRECIO_LABIAL);
            base = nuevaVariante(producto, "Nude", "30 ml", PRECIO_BASE);
            paleta = nuevaVariante(producto, "Bronce", "12 tonos", PRECIO_PALETA);
            // Sin carga inicial: costo_promedio en 0. No se puede vender.
            sinCosto = nuevaVariante(producto, "Coral", "5 ml", PRECIO_LABIAL);

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(labial, 40, COSTO_LABIAL),
                    new PeticionesInventario.CargaInicial.Linea(base, 20, COSTO_BASE),
                    new PeticionesInventario.CargaInicial.Linea(paleta, 4, COSTO_PALETA))),
                    idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    // ------------------------------------------------------- 1. sin caja abierta

    /**
     * <strong>Ninguna venta existe fuera de una sesión de caja abierta.</strong> Y el
     * sistema no la abre por su cuenta: abrir caja es un acto con una base que alguien
     * contó, no un efecto colateral de cobrar.
     */
    @Test
    @Order(1)
    void sinSesionAbiertaNoSeVende() {
        Respuesta respuesta = cobrar(nuevoUuid(), "EFECTIVO", 50_000L, linea(labial, 1));

        System.out.println("VERIFICACION vender sin caja abierta => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SIN_SESION_ABIERTA");
        assertThat(ventaRepository.count()).isZero();
        assertThat(sesionRepository.count()).isZero();
    }

    // --------------------------------------------------- 2. la caja de ayer abierta

    /**
     * El agujero que el bloqueo de apertura no tapaba: <strong>para vender nadie
     * necesita abrir caja</strong>. Con la sesión de ayer todavía abierta, el punto de
     * venta simplemente la encuentra, y las ventas de hoy entran en el arqueo de ayer.
     * El descuadre no se ve: esa sesión cuadra consigo misma, solo que abarca dos días.
     */
    @Test
    @Order(2)
    void conLaCajaDeAyerAbiertaNoSeVende() {
        SesionCaja deAyer = new SesionCaja();
        deAyer.setConsecutivo("S-000999");
        deAyer.setUsuarioApertura(usuarioRepository.findById(idDuena).orElseThrow());
        deAyer.setFechaApertura(Fechas.ahora().minusDays(1));
        deAyer.setBaseInicial(180_000);
        deAyer.setEstado(EstadoSesionCaja.ABIERTA);
        idSesionDeAyer = sesionRepository.save(deAyer).getId();

        Respuesta respuesta = cobrar(nuevoUuid(), "EFECTIVO", 50_000L, linea(labial, 1));

        System.out.println("VERIFICACION vender con la caja de ayer abierta => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SESION_ABIERTA_DE_DIA_ANTERIOR");
        assertThat(ventaRepository.count()).isZero();
    }

    @Test
    @Order(3)
    void cerradaLaDeAyerSeAbreLaDeHoy() {
        Respuesta cierre = duena.post("/api/v1/caja/sesiones/" + idSesionDeAyer + "/cierre",
                "{\"conteo\":[{\"denominacion\":50000,\"cantidad\":3},"
                        + "{\"denominacion\":10000,\"cantidad\":3}],"
                        + "\"montoRetirado\":0,\"baseSiguiente\":180000}");
        assertThat(cierre.estado()).isEqualTo(200);

        Respuesta apertura = duena.post("/api/v1/caja/sesiones", "{\"baseInicial\":180000}");
        System.out.println("VERIFICACION caja de hoy abierta => " + apertura.estado());
        assertThat(apertura.estado()).isEqualTo(201);
    }

    // ------------------------------------------------------------ 3. el cobro

    /**
     * La venta completa: cabecera, líneas congeladas, salida de inventario y entrada al
     * cajón, todo de una vez.
     *
     * <p>Lo que se comprueba contra la base y no contra la respuesta es el congelado:
     * el costo no sale en ningún DTO —a propósito— así que la única forma de verificar
     * que quedó guardado es leer {@code venta_item}. Si no se congelara, cambiar el
     * precio del labial mañana movería el margen de esta venta de hoy, y nadie lo
     * notaría nunca.
     */
    @Test
    @Order(4)
    void laVentaEsUnaSolaTransaccionYCongelaPrecioCostoYDescripcion() {
        long stockAntes = movimientoRepository.stockDe(labial);
        String uuid = nuevoUuid();

        Respuesta respuesta = cobrar(uuid, "EFECTIVO", 110_000L,
                linea(labial, 2) + "," + linea(base, 1));

        long total = 2 * PRECIO_LABIAL + PRECIO_BASE;   // 102.300
        System.out.println("VERIFICACION venta => " + respuesta.estado() + " "
                + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo())
                .contains("\"consecutivo\":\"V-000001\"")
                .contains("\"subtotal\":" + total)
                .contains("\"total\":" + total)
                .contains("\"estado\":\"COMPLETADA\"")
                .contains("\"rutaRecibo\":null")
                .contains("Maybelline Labial mate Rojo 5 ml");

        Venta venta = ventaRepository.findByUuid(uuid).orElseThrow();
        List<VentaItem> items = itemRepository.findByVentaId(venta.getId());

        assertThat(items).hasSize(2);
        VentaItem itemLabial = items.stream()
                .filter(i -> i.getVariante().getId().equals(labial)).findFirst().orElseThrow();
        assertThat(itemLabial.getPrecioUnitarioCongelado()).isEqualTo(PRECIO_LABIAL);
        assertThat(itemLabial.getCostoUnitarioCongelado()).isEqualTo(COSTO_LABIAL);
        assertThat(itemLabial.getDescripcionCongelada()).isEqualTo("Maybelline Labial mate Rojo 5 ml");

        // Inventario: una salida por línea, con la cantidad negativa.
        List<MovimientoInventario> movimientos = movimientoRepository.findByVentaId(venta.getId());
        assertThat(movimientos).hasSize(2);
        assertThat(movimientos).allMatch(m -> m.getTipo() == TipoMovimientoInventario.VENTA);
        assertThat(movimientos).allMatch(m -> m.getCantidad() < 0);
        assertThat(movimientoRepository.stockDe(labial)).isEqualTo(stockAntes - 2);

        // Cajón: un solo movimiento, positivo y por el total.
        var enCaja = movimientoCajaRepository.findByVentaId(venta.getId());
        System.out.println("    movimientos de caja de la venta: " + enCaja.size());
        assertThat(enCaja).hasSize(1);
        assertThat(enCaja.get(0).getTipo()).isEqualTo(TipoMovimientoCaja.VENTA_EFECTIVO);
        assertThat(enCaja.get(0).getMonto()).isEqualTo(total);
    }

    /** El cambio lo calcula el servidor. La petición ni siquiera tiene campo para él. */
    @Test
    @Order(5)
    void elCambioLoCalculaElServidorYElEfectivoTieneQueAlcanzar() {
        long total = PRECIO_PALETA;   // 52.000

        Respuesta corta = cobrar(nuevoUuid(), "EFECTIVO", 50_000L, linea(paleta, 1));
        System.out.println("VERIFICACION efectivo insuficiente => " + corta.estado()
                + " " + corta.cuerpo());
        assertThat(corta.estado()).isEqualTo(400);
        assertThat(corta.cuerpo()).contains("no alcanza para el total");

        Respuesta buena = cobrar(nuevoUuid(), "EFECTIVO", 100_000L, linea(paleta, 1));
        System.out.println("VERIFICACION cambio => " + buena.cuerpo());
        assertThat(buena.estado()).isEqualTo(201);
        assertThat(buena.cuerpo())
                .contains("\"efectivoRecibido\":100000")
                .contains("\"cambio\":" + (100_000 - total));
    }

    /**
     * <strong>Solo el efectivo toca el cajón.</strong> Los otros cuatro medios entran
     * en la sesión pero se concilian aparte contra el extracto. Si generaran movimiento
     * de caja, el arqueo pediría plata física que nunca entró y toda sesión cerraría
     * con faltante.
     */
    @Test
    @Order(6)
    void soloElEfectivoGeneraMovimientoDeCaja() {
        for (String metodo : List.of("TARJETA", "NEQUI", "DAVIPLATA", "TRANSFERENCIA")) {
            String uuid = nuevoUuid();
            Respuesta respuesta = cobrar(uuid, metodo, null, linea(base, 1));

            Venta venta = ventaRepository.findByUuid(uuid).orElseThrow();
            var enCaja = movimientoCajaRepository.findByVentaId(venta.getId());

            System.out.println("VERIFICACION " + metodo + " => " + respuesta.estado()
                    + ", movimientos de caja: " + enCaja.size());
            assertThat(respuesta.estado()).isEqualTo(201);
            assertThat(enCaja)
                    .withFailMessage("%s generó %d movimiento(s) de caja: solo el efectivo "
                            + "toca el cajón", metodo, enCaja.size())
                    .isEmpty();
            // Y sin efectivo no hay ni recibido ni cambio.
            assertThat(respuesta.cuerpo())
                    .contains("\"efectivoRecibido\":null")
                    .contains("\"cambio\":null");
        }
    }

    /**
     * <strong>Idempotencia.</strong> El uuid lo genera el cliente al abrir el carrito.
     * Repetir la petición —la respuesta se perdió, alguien pulsó dos veces— devuelve la
     * misma venta y no cobra de nuevo.
     *
     * <p>201 la primera vez, 200 la segunda: es lo que le permite al front notar que
     * reutilizó un uuid, en vez de que un segundo cobro legítimo se trague en silencio.
     */
    @Test
    @Order(7)
    void dosPostIdenticosDejanUnaSolaVenta() {
        String uuid = nuevoUuid();
        String cuerpo = linea(labial, 3);
        long ventasAntes = ventaRepository.count();
        long stockAntes = movimientoRepository.stockDe(labial);
        long enCajaAntes = movimientoCajaRepository.count();

        Respuesta primera = cobrar(uuid, "EFECTIVO", 200_000L, cuerpo);
        Respuesta segunda = cobrar(uuid, "EFECTIVO", 200_000L, cuerpo);

        Venta venta = ventaRepository.findByUuid(uuid).orElseThrow();

        System.out.println("VERIFICACION idempotencia => " + primera.estado() + " y "
                + segunda.estado()
                + " | ventas +" + (ventaRepository.count() - ventasAntes)
                + " | stock " + stockAntes + " -> " + movimientoRepository.stockDe(labial)
                + " | movimientos de caja +" + (movimientoCajaRepository.count() - enCajaAntes));

        assertThat(primera.estado()).isEqualTo(201);
        assertThat(segunda.estado()).isEqualTo(200);
        assertThat(ventaRepository.count()).isEqualTo(ventasAntes + 1);
        assertThat(movimientoRepository.findByVentaId(venta.getId())).hasSize(1);
        assertThat(movimientoCajaRepository.findByVentaId(venta.getId())).hasSize(1);
        assertThat(movimientoRepository.stockDe(labial)).isEqualTo(stockAntes - 3);
        // Y la respuesta repetida es la misma venta, no una nueva.
        assertThat(segunda.cuerpo()).contains("\"id\":" + venta.getId());
    }

    /**
     * <strong>Vender sin stock no se bloquea.</strong> Con una clienta enfrente,
     * bloquear empuja a un ajuste improvisado que borra la evidencia del descuadre. Lo
     * que sí pasa es que la respuesta nombra la variante que quedó negativa, para que
     * la pantalla avise y quede algo que averiguar.
     */
    @Test
    @Order(8)
    void venderSinStockNoSeBloqueaYSeAvisa() {
        long stock = movimientoRepository.stockDe(paleta);
        int cantidad = (int) stock + 3;

        Respuesta respuesta = cobrar(nuevoUuid(), "TARJETA", null, linea(paleta, cantidad));

        System.out.println("VERIFICACION vender " + cantidad + " con stock " + stock
                + " => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(movimientoRepository.stockDe(paleta)).isEqualTo(-3);
        assertThat(respuesta.cuerpo())
                .contains("\"variantesEnNegativo\":[{\"varianteId\":" + paleta)
                .contains("\"stock\":-3");
    }

    /**
     * Una variante con {@code costo_promedio} en 0 no se vende, y el mensaje dice qué
     * hacer.
     *
     * <p>El cero significa que nunca entró mercancía valorada, o que todas las compras
     * que la valoraban se anularon y el replay dejó el promedio otra vez en cero.
     * Cobrarla congelaría costo 0 y la métrica de margen diría para siempre que ese
     * producto se vendió con 100% de utilidad.
     */
    @Test
    @Order(9)
    void unaVarianteSinCostoNoSeVendeYElMensajeDiceQueHacer() {
        long ventasAntes = ventaRepository.count();
        Respuesta respuesta = cobrar(nuevoUuid(), "EFECTIVO", 50_000L, linea(sinCosto, 1));

        System.out.println("VERIFICACION variante sin costo => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo())
                .contains("VARIANTE_SIN_COSTO")
                .contains("Coral")
                .contains("recibir la compra pendiente");
        assertThat(ventaRepository.count()).isEqualTo(ventasAntes);
    }

    /**
     * Una venta sin líneas no llega ni a crearse. Si llegara, quemaría un consecutivo y
     * dejaría un documento de total 0 sin nada dentro: un hueco en la numeración, que
     * es justo lo que la tabla {@code consecutivo} existe para evitar.
     */
    @Test
    @Order(10)
    void unaVentaSinLineasNoSeCrea() {
        long ventasAntes = ventaRepository.count();

        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + nuevoUuid() + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":10000,\"lineas\":[]}");

        System.out.println("VERIFICACION venta sin líneas => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("al menos una línea");
        assertThat(ventaRepository.count()).isEqualTo(ventasAntes);
    }

    /**
     * Las dos igualdades del prorrateo, pero contra lo que quedó <strong>guardado</strong>.
     *
     * <p>{@code DescuentoProrrateadoTest} demuestra que la aritmética es exacta; esto
     * demuestra que el servicio la usa y congela el resultado, en vez de recalcularlo
     * cada vez que alguien pregunta.
     */
    @Test
    @Order(11)
    void elDescuentoRepartidoCuadraConLaCabeceraEnLaBase() {
        String uuid = nuevoUuid();
        long descuento = 25_000;

        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + uuid + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":500000,\"descuento\":" + descuento + ","
                        + "\"lineas\":[" + linea(labial, 3) + "," + linea(base, 2) + ","
                        + linea(paleta, 1) + "]}");
        assertThat(respuesta.estado()).isEqualTo(201);

        Venta venta = ventaRepository.findByUuid(uuid).orElseThrow();
        List<VentaItem> items = itemRepository.findByVentaId(venta.getId());

        long sumaDescuentos = items.stream().mapToLong(VentaItem::getDescuentoProrrateado).sum();
        long sumaNetas = items.stream()
                .mapToLong(i -> i.getSubtotal() - i.getDescuentoProrrateado()).sum();

        System.out.println("VERIFICACION prorrateo guardado => SUM(prorrateado)=" + sumaDescuentos
                + " descuento=" + venta.getDescuento() + " | SUM(neto)=" + sumaNetas
                + " total=" + venta.getTotal());

        assertThat(sumaDescuentos).isEqualTo(venta.getDescuento()).isEqualTo(descuento);
        assertThat(sumaNetas).isEqualTo(venta.getTotal());
        assertThat(venta.getTotal()).isEqualTo(venta.getSubtotal() - descuento);
    }

    /** Un descuento mayor que el subtotal daría un total negativo. */
    @Test
    @Order(12)
    void elDescuentoNoPuedeSuperarElSubtotal() {
        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + nuevoUuid() + "\",\"metodoPago\":\"TARJETA\","
                        + "\"descuento\":999999,\"lineas\":[" + linea(base, 1) + "]}");

        System.out.println("VERIFICACION descuento > subtotal => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("mayor que el subtotal");
    }

    /**
     * Un obsequio: descuento del 100%, total 0, en efectivo.
     *
     * <p>El sistema lo permite —{@code descuento <= subtotal} admite la igualdad— y no
     * puede reventar: sin la guarda, el movimiento de caja de monto 0 viola el
     * {@code CHECK (monto <> 0)} en el flush, ya con la venta y su inventario escritos,
     * y la clienta se va sin su obsequio y con un 500 en pantalla.
     *
     * <p>El inventario sí sale, que es lo correcto: el producto se fue de la vitrina
     * aunque no se haya cobrado.
     */
    @Test
    @Order(13)
    void unaVentaDeTotalCeroNoEscribeEnElCajonPeroSiEnElInventario() {
        long stockAntes = movimientoRepository.stockDe(base);
        String uuid = nuevoUuid();

        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + uuid + "\",\"metodoPago\":\"EFECTIVO\",\"efectivoRecibido\":0,"
                        + "\"descuento\":" + PRECIO_BASE + ",\"lineas\":["
                        + linea(base, 1) + "]}");

        System.out.println("VERIFICACION obsequio (total 0, efectivo) => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("\"total\":0").contains("\"cambio\":0");

        Venta venta = ventaRepository.findByUuid(uuid).orElseThrow();
        assertThat(movimientoCajaRepository.findByVentaId(venta.getId())).isEmpty();
        assertThat(movimientoRepository.stockDe(base)).isEqualTo(stockAntes - 1);
    }

    // ------------------------------------------------------------------- apoyo

    private Respuesta cobrar(String uuid, String metodo, Long recibido, String lineas) {
        String efectivo = recibido == null ? "" : ",\"efectivoRecibido\":" + recibido;
        return duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + uuid + "\",\"metodoPago\":\"" + metodo + "\"" + efectivo
                        + ",\"lineas\":[" + lineas + "]}");
    }

    private String linea(Long varianteId, int cantidad) {
        return "{\"varianteId\":" + varianteId + ",\"cantidad\":" + cantidad + "}";
    }

    private String nuevoUuid() {
        return UUID.randomUUID().toString();
    }

    private Long nuevaVariante(Producto producto, String tono, String tamano, long precio) {
        Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), tono, tamano, null, precio, 2, null, null));
        return variante.getId();
    }
}
