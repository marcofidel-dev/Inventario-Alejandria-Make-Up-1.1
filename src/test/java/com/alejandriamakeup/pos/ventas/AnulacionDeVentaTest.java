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
import com.alejandriamakeup.pos.caja.MovimientoCaja;
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
 * Anular una venta: devolver el inventario, devolver la plata, y no tocar nunca una
 * sesión de caja ya cerrada.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class AnulacionDeVentaTest {

    private static final String URL = BaseDatosAislada.urlNueva("anulacion-venta");

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

    private static final long PRECIO = 38_900;
    private static final long COSTO = 20_100;

    private static Long labial;
    private static Long idSesionUno;
    private static Long idSesionDos;

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

            Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, PRECIO, 2, null, null));
            labial = variante.getId();

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(labial, 100, COSTO))), idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    @Order(1)
    void abrirLaPrimeraCaja() {
        Respuesta apertura = duena.post("/api/v1/caja/sesiones", "{\"baseInicial\":100000}");
        assertThat(apertura.estado()).isEqualTo(201);
        idSesionUno = sesionRepository.buscarAbierta().orElseThrow().getId();
    }

    /**
     * Lo básico: el inventario vuelve, la plata sale del cajón y queda escrito por qué.
     *
     * <p>El movimiento de devolución es <strong>nuevo</strong>, no una corrección del
     * anterior: los dos ledgers son append-only, así que la venta y su anulación
     * conviven y la historia queda completa. Borrar la salida original haría que el
     * stock cuadrara igual, pero nadie podría reconstruir que hubo una venta anulada.
     */
    @Test
    @Order(2)
    void anularDevuelveElInventarioYLaPlataYDejaConstanciaDelMotivo() {
        long stockAntes = movimientoRepository.stockDe(labial);
        long venta = vender("EFECTIVO", 2, 100_000L);
        long total = 2 * PRECIO;

        assertThat(movimientoRepository.stockDe(labial)).isEqualTo(stockAntes - 2);

        Respuesta respuesta = duena.post("/api/v1/ventas/" + venta + "/anulacion",
                "{\"motivo\":\"Se cobro el tono equivocado\"}");

        List<MovimientoCaja> enCaja = movimientoCajaRepository.findByVentaId(venta);
        System.out.println("VERIFICACION anulación => " + respuesta.estado() + " "
                + respuesta.cuerpo());
        System.out.println("    stock " + stockAntes + " -> "
                + movimientoRepository.stockDe(labial) + " | movimientos de caja: "
                + enCaja.stream().map(m -> m.getTipo() + " " + m.getMonto()).toList());

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"estado\":\"ANULADA\"")
                .contains("\"motivoAnulacion\":\"Se cobro el tono equivocado\"")
                .contains("\"fechaAnulacion\":\"");

        // Inventario devuelto, con la salida original todavía en el ledger.
        assertThat(movimientoRepository.stockDe(labial)).isEqualTo(stockAntes);
        assertThat(movimientoRepository.findByVentaId(venta)).hasSize(2);
        assertThat(movimientoRepository.findByVentaId(venta))
                .anyMatch(m -> m.getTipo() == TipoMovimientoInventario.VENTA && m.getCantidad() == -2)
                .anyMatch(m -> m.getTipo() == TipoMovimientoInventario.ANULACION && m.getCantidad() == 2);

        // Cajón: entró el total y salió el total.
        assertThat(enCaja).hasSize(2);
        assertThat(enCaja).anyMatch(m -> m.getTipo() == TipoMovimientoCaja.VENTA_EFECTIVO
                && m.getMonto() == total);
        assertThat(enCaja).anyMatch(m -> m.getTipo() == TipoMovimientoCaja.ANULACION
                && m.getMonto() == -total);
    }

    /**
     * {@code COMPLETADA -> ANULADA} es terminal. Sin esta guarda, dos peticiones
     * seguidas devolverían el inventario dos veces y sacarían del cajón el doble de lo
     * que entró — y el segundo movimiento parecería tan legítimo como el primero.
     */
    @Test
    @Order(3)
    void noSePuedeAnularDosVeces() {
        long venta = vender("EFECTIVO", 1, 50_000L);
        duena.post("/api/v1/ventas/" + venta + "/anulacion", "{\"motivo\":\"primera\"}");

        long stockTrasLaPrimera = movimientoRepository.stockDe(labial);
        Respuesta segunda = duena.post("/api/v1/ventas/" + venta + "/anulacion",
                "{\"motivo\":\"segunda\"}");

        System.out.println("VERIFICACION doble anulación => " + segunda.estado() + " "
                + segunda.cuerpo());
        assertThat(segunda.estado()).isEqualTo(409);
        assertThat(segunda.cuerpo()).contains("ESTADO_DE_VENTA_INVALIDO");
        assertThat(movimientoRepository.stockDe(labial)).isEqualTo(stockTrasLaPrimera);
        assertThat(movimientoCajaRepository.findByVentaId(venta)).hasSize(2);
    }

    @Test
    @Order(4)
    void elMotivoEsObligatorio() {
        long venta = vender("EFECTIVO", 1, 50_000L);

        Respuesta vacio = duena.post("/api/v1/ventas/" + venta + "/anulacion",
                "{\"motivo\":\"   \"}");

        System.out.println("VERIFICACION anular sin motivo => " + vacio.estado() + " "
                + vacio.cuerpo());
        assertThat(vacio.estado()).isEqualTo(400);
        assertThat(vacio.cuerpo()).contains("El motivo es obligatorio");
        assertThat(ventaRepository.findById(venta).orElseThrow().getEstado())
                .isEqualTo(EstadoVenta.COMPLETADA);
    }

    /** Lo que no entró al cajón tampoco sale de él. */
    @Test
    @Order(5)
    void anularUnaVentaQueNoFueEnEfectivoNoTocaElCajon() {
        long venta = vender("TARJETA", 1, null);

        Respuesta respuesta = duena.post("/api/v1/ventas/" + venta + "/anulacion",
                "{\"motivo\":\"la clienta se arrepintio\"}");

        System.out.println("VERIFICACION anular venta por TARJETA => " + respuesta.estado()
                + ", movimientos de caja: " + movimientoCajaRepository.findByVentaId(venta).size());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(movimientoCajaRepository.findByVentaId(venta)).isEmpty();
    }

    /**
     * <strong>El escenario exacto: la sesión original ya cerró.</strong>
     *
     * <p>La plata sale de la caja que está abierta ahora, nunca de la que ya se cuadró.
     * Una sesión cerrada es inmutable: su efectivo esperado y su diferencia quedaron
     * congelados y firmados en el arqueo. Meterle un movimiento después haría que los
     * números de un cierre ya revisado dejaran de cuadrar con su propia lista de
     * movimientos, y el descubrimiento llegaría semanas más tarde sin forma de saber
     * qué pasó.
     *
     * <p>Que sea la sesión de hoy no es una rama especial del código: es el único
     * camino, y cuando la sesión original sigue abierta resulta ser la misma. Un caso
     * raro que comparte camino con el común es un caso raro que sí se ejercita.
     */
    @Test
    @Order(6)
    void anularUnaVentaDeSesionCerradaGolpeaLaSesionActual() {
        long venta = vender("EFECTIVO", 2, 100_000L);
        long total = 2 * PRECIO;

        // Se cierra la caja del día con esa venta adentro.
        Respuesta cierre = duena.post("/api/v1/caja/sesiones/" + idSesionUno + "/cierre",
                "{\"conteo\":[{\"denominacion\":50000,\"cantidad\":10}],"
                        + "\"montoRetirado\":0,\"baseSiguiente\":100000}");
        assertThat(cierre.estado()).isEqualTo(200);

        SesionCaja cerrada = sesionRepository.findById(idSesionUno).orElseThrow();
        long esperadoCongelado = cerrada.getEfectivoEsperado();
        long diferenciaCongelada = cerrada.getDiferencia();
        int movimientosDeLaCerrada = movimientoCajaRepository
                .findBySesionIdOrderByFechaAsc(idSesionUno).size();

        // Al día siguiente se abre otra caja y ahí se descubre el error.
        Respuesta apertura = duena.post("/api/v1/caja/sesiones", "{\"baseInicial\":100000}");
        assertThat(apertura.estado()).isEqualTo(201);
        idSesionDos = sesionRepository.buscarAbierta().orElseThrow().getId();

        Respuesta anulacion = duena.post("/api/v1/ventas/" + venta + "/anulacion",
                "{\"motivo\":\"cobro duplicado detectado al dia siguiente\"}");
        assertThat(anulacion.estado()).isEqualTo(200);

        MovimientoCaja devolucion = movimientoCajaRepository.findByVentaId(venta).stream()
                .filter(m -> m.getTipo() == TipoMovimientoCaja.ANULACION)
                .findFirst().orElseThrow();
        SesionCaja despues = sesionRepository.findById(idSesionUno).orElseThrow();

        System.out.println("VERIFICACION anular contra sesión cerrada:");
        System.out.println("    la venta era de la sesión " + idSesionUno
                + ", la devolución cayó en la " + devolucion.getSesion().getId()
                + " (la abierta es la " + idSesionDos + ")");
        System.out.println("    la sesión cerrada sigue con esperado " + despues.getEfectivoEsperado()
                + " y diferencia " + despues.getDiferencia());

        assertThat(devolucion.getSesion().getId())
                .withFailMessage("La devolución cayó en la sesión %d, que ya está cerrada. "
                        + "Una sesión cerrada es inmutable: la plata sale del cajón de hoy.",
                        devolucion.getSesion().getId())
                .isEqualTo(idSesionDos);
        assertThat(devolucion.getMonto()).isEqualTo(-total);

        // La sesión cerrada no se enteró de nada.
        assertThat(movimientoCajaRepository.findBySesionIdOrderByFechaAsc(idSesionUno))
                .hasSize(movimientosDeLaCerrada);
        assertThat(despues.getEfectivoEsperado()).isEqualTo(esperadoCongelado);
        assertThat(despues.getDiferencia()).isEqualTo(diferenciaCongelada);
    }

    /**
     * El desglose por método de pago <strong>no aparece con la sesión abierta</strong>.
     *
     * <p>No es pudor: sumado a la base inicial —que el front conoce, porque él mismo la
     * envió al abrir— el desglose reconstruye el efectivo esperado al peso, y el cierre
     * a ciegas deja de ser ciego. Por eso vive en {@code ArqueoDto}, que solo devuelve
     * el cierre, y no como un campo de la sesión.
     */
    @Test
    @Order(7)
    void elDesgloseNoSeExponeConLaSesionAbierta() {
        List<String> rutas = List.of(
                "/api/v1/caja/sesiones/actual",
                "/api/v1/caja/sesiones",
                "/api/v1/caja/sesiones/" + idSesionDos,
                "/api/v1/caja/sesiones/" + idSesionDos + "/movimientos");

        for (String ruta : rutas) {
            String cuerpo = duena.get(ruta).cuerpo();
            System.out.println("VERIFICACION " + ruta + " sin desglose: "
                    + !cuerpo.contains("ventasPorMetodo"));
            assertThat(cuerpo)
                    .withFailMessage("%s expone el desglose con la sesión abierta: %s", ruta, cuerpo)
                    .doesNotContain("ventasPorMetodo");
        }
    }

    /**
     * El desglose del cierre <strong>excluye las ventas anuladas</strong>.
     *
     * <p>Una venta anulada ya devolvió su plata con un movimiento de signo contrario.
     * Contarla también en el desglose la sumaría dos veces, y el renglón de efectivo
     * dejaría de cuadrar contra el efectivo esperado justo en el momento en que alguien
     * lo usa para entender un faltante.
     */
    @Test
    @Order(8)
    void elDesgloseDelCierreExcluyeLasVentasAnuladas() {
        long efectivoBueno = vender("EFECTIVO", 1, 50_000L);
        long tarjetaAnulada = vender("TARJETA", 1, null);
        duena.post("/api/v1/ventas/" + tarjetaAnulada + "/anulacion",
                "{\"motivo\":\"cobro mal hecho\"}");

        Respuesta cierre = duena.post("/api/v1/caja/sesiones/" + idSesionDos + "/cierre",
                "{\"conteo\":[{\"denominacion\":50000,\"cantidad\":2}],"
                        + "\"montoRetirado\":0,\"baseSiguiente\":100000}");

        System.out.println("VERIFICACION desglose del cierre => " + cierre.cuerpo());
        assertThat(cierre.estado()).isEqualTo(200);
        assertThat(cierre.cuerpo()).contains("ventasPorMetodo");

        // La de efectivo cuenta; la de tarjeta, anulada, no aparece por ningún lado.
        assertThat(cierre.cuerpo())
                .contains("{\"metodo\":\"EFECTIVO\",\"cantidad\":1,\"total\":" + PRECIO + "}");
        assertThat(cierre.cuerpo())
                .withFailMessage("El desglose incluye la venta anulada por TARJETA: %s",
                        cierre.cuerpo())
                .doesNotContain("TARJETA");

        assertThat(ventaRepository.findById(efectivoBueno).orElseThrow().getEstado())
                .isEqualTo(EstadoVenta.COMPLETADA);
    }

    // ------------------------------------------------------------------- apoyo

    private long vender(String metodo, int cantidad, Long recibido) {
        String efectivo = recibido == null ? "" : ",\"efectivoRecibido\":" + recibido;
        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"" + metodo + "\""
                        + efectivo + ",\"lineas\":[{\"varianteId\":" + labial
                        + ",\"cantidad\":" + cantidad + "}]}");
        assertThat(respuesta.estado()).isEqualTo(201);
        return Long.parseLong(respuesta.cuerpo().replaceFirst("^\\{\"id\":(\\d+).*$", "$1"));
    }
}
