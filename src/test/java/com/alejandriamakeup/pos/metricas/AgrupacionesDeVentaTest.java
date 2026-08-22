package com.alejandriamakeup.pos.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

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
import com.alejandriamakeup.pos.caja.SesionCajaRepository;
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
import com.alejandriamakeup.pos.metricas.dto.MetricasDto;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.MetodoPago;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaRepository;
import com.alejandriamakeup.pos.ventas.VentasPorMetodo;

/**
 * Las dos agrupaciones: por fecha de venta (métricas) y por sesión de caja (arqueo).
 *
 * <p>Son la misma pregunta con distinta agrupación, y hacen falta las dos pruebas,
 * porque cada una atrapa un fallo distinto:
 *
 * <ul>
 *   <li><strong>Coinciden cuando deben coincidir.</strong> Con una sesión que abre y
 *       cierra el mismo día, los dos desgloses tienen que salir idénticos. Es lo que
 *       cae si las fórmulas se separan — si una olvidara el descuento, o contara una
 *       anulada, o sumara líneas donde la otra suma ventas. Dos números que deberían
 *       ser el mismo y no lo son, sin nadie que lo note.
 *   <li><strong>Difieren cuando deben diferir.</strong> Con la sesión abierta de un
 *       día para otro, tienen que dar distinto, y eso no es un error: la sesión
 *       contiene ventas de dos fechas. Si salieran iguales, alguna de las dos estaría
 *       agrupando por lo que no dice.
 * </ul>
 *
 * <p>El primer caso es lo que le permite a la dueña confiar en los dos informes; el
 * segundo, entender por qué el informe del lunes no cuadra con el arqueo del lunes
 * sin tener que llamar a nadie.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class AgrupacionesDeVentaTest {

    private static final String URL = BaseDatosAislada.urlNueva("metricas-agrupaciones");

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
    private VentaRepository ventaRepository;

    @Autowired
    private SesionCajaRepository sesionRepository;

    private static Long idSesion;

    @BeforeEach
    void sembrar() {
        if (idSesion != null) {
            return;
        }

        long idDuena = crearDuena();

        Marca marca = servicioMarca.crear("Vogue");
        Categoria categoria = servicioCategoria.crear("Ojos");
        Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Sombra", marca.getId(), categoria.getId(), null));
        Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Bronce", "3 g", null, 10_000L, 0, null, null));

        servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(variante.getId(), 50, 4_000L))),
                idDuena);

        ClienteHttpDePrueba duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        duena.post("/api/v1/caja/sesiones", "{\"baseInicial\":100000}");

        // 20.000 en efectivo, 10.000 en efectivo, y 30.000 con 5.000 de descuento en
        // tarjeta. El descuento está ahí a propósito: es donde las dos fórmulas se
        // separarían si una de las dos lo olvidara.
        vender(duena, "una", "EFECTIVO", variante.getId(), 2, null);
        vender(duena, "dos", "EFECTIVO", variante.getId(), 1, null);
        vender(duena, "tres", "TARJETA", variante.getId(), 3, 5_000L);

        idSesion = sesionRepository.buscarAbierta().orElseThrow().getId();
    }

    @Order(1)
    @Test
    void conLaSesionDentroDeUnDiaLosDosDesglosesSonIdenticos() {
        List<VentasPorMetodo> porSesion = ventaRepository.desglosePorMetodo(idSesion);
        List<VentasPorMetodo> porFecha = panel(LocalDate.now()).porMetodoPago();

        System.out.println("VERIFICACION por sesión => " + porSesion);
        System.out.println("VERIFICACION por fecha  => " + porFecha);

        assertThat(porFecha)
                .withFailMessage("La sesión abrió y cerró el mismo día, así que el arqueo dice "
                        + "%s y las métricas dicen %s para lo mismo. Las dos fórmulas se "
                        + "separaron.", porSesion, porFecha)
                .isEqualTo(porSesion);

        // Y que no sean iguales por estar las dos vacías o las dos mal.
        assertThat(porFecha).containsExactly(
                new VentasPorMetodo(MetodoPago.EFECTIVO, 2, 30_000),
                new VentasPorMetodo(MetodoPago.TARJETA, 1, 25_000));
    }

    @Order(2)
    @Test
    void conLaSesionAbiertaDeUnDiaParaOtroDanDistinto() {
        // La sesión se quedó abierta: la primera venta fue anoche. Nada más cambia —
        // sigue siendo la misma sesión, con las mismas tres ventas.
        Venta deAnoche = ventaRepository.findByConsecutivo("V-000001").orElseThrow();
        deAnoche.setFecha(deAnoche.getFecha().minusDays(1));
        ventaRepository.save(deAnoche);

        List<VentasPorMetodo> porSesion = ventaRepository.desglosePorMetodo(idSesion);
        List<VentasPorMetodo> deHoy = panel(LocalDate.now()).porMetodoPago();
        List<VentasPorMetodo> deAyer = panel(LocalDate.now().minusDays(1)).porMetodoPago();

        System.out.println("VERIFICACION por sesión => " + porSesion);
        System.out.println("VERIFICACION hoy        => " + deHoy);
        System.out.println("VERIFICACION ayer       => " + deAyer);

        assertThat(deHoy)
                .withFailMessage("La sesión contiene ventas de dos fechas y sin embargo el "
                        + "desglose del día salió igual al de la sesión (%s). Alguna de las dos "
                        + "no está agrupando por lo que dice.", deHoy)
                .isNotEqualTo(porSesion);

        // La sesión sigue teniendo las tres ventas; el día de hoy, solo dos.
        assertThat(porSesion).containsExactly(
                new VentasPorMetodo(MetodoPago.EFECTIVO, 2, 30_000),
                new VentasPorMetodo(MetodoPago.TARJETA, 1, 25_000));
        assertThat(deHoy).containsExactly(
                new VentasPorMetodo(MetodoPago.EFECTIVO, 1, 10_000),
                new VentasPorMetodo(MetodoPago.TARJETA, 1, 25_000));
        assertThat(deAyer).containsExactly(
                new VentasPorMetodo(MetodoPago.EFECTIVO, 1, 20_000));

        // Y el panel declara cuál usa, para que la pantalla pueda decirlo.
        assertThat(panel(LocalDate.now()).agrupacion())
                .isEqualTo(MetricasDto.Agrupacion.FECHA_DE_VENTA);
    }

    private MetricasDto.Panel panel(LocalDate fecha) {
        return servicioMetricas.panel(Periodo.DIA, fecha);
    }

    private void vender(ClienteHttpDePrueba duena, String uuid, String metodo,
                        long varianteId, int cantidad, Long descuento) {
        duena.post("/api/v1/ventas", "{\"uuid\":\"" + uuid + "\",\"metodoPago\":\"" + metodo
                + "\"" + (descuento == null ? "" : ",\"descuento\":" + descuento)
                + ",\"efectivoRecibido\":100000,\"lineas\":[{\"varianteId\":" + varianteId
                + ",\"cantidad\":" + cantidad + "}]}");
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
