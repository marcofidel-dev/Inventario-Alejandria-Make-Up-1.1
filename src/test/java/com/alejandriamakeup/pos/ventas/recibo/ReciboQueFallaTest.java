package com.alejandriamakeup.pos.ventas.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.ventas.GeneradorComprobante;
import com.alejandriamakeup.pos.ventas.Venta;
import com.alejandriamakeup.pos.ventas.VentaRepository;
import com.alejandriamakeup.pos.caja.MovimientoCajaRepository;

/**
 * <strong>Un PDF que no se puede escribir no tumba un cobro ya realizado.</strong>
 *
 * <p>Es la regla que solo se descubre el día que pasa: el disco lleno, la carpeta que
 * el cliente de OneDrive tiene bloqueada, los permisos que cambió una actualización de
 * Windows. Si la generación se hiciera dentro de la transacción de la venta, o si
 * dejara escapar la excepción, la clienta habría pagado, se habría llevado el producto
 * y en pantalla habría un error — con el inventario sin descontar y el cajón sin la
 * plata.
 *
 * <p>El fallo se provoca con un generador que lanza, que es la única forma de
 * reproducir un disco lleno sin llenar un disco.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReciboQueFallaTest {

    private static final String URL = BaseDatosAislada.urlNueva("recibo-que-falla");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @MockitoBean
    private GeneradorComprobante generador;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private VentaRepository ventaRepository;
    @Autowired private MovimientoInventarioRepository movimientoRepository;
    @Autowired private MovimientoCajaRepository movimientoCajaRepository;

    private static Long idLabial;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void sembrarYEntrar() {
        if (idLabial == null) {
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
            Variante labial = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, 38_900L, 2, null, null));
            idLabial = labial.getId();

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(idLabial, 40, 20_100L))), idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void siElReciboFallaLaVentaSigueSiendoValidaYQuedaRecuperable() {
        when(generador.generar(any(), any()))
                .thenThrow(new UncheckedIOException(new IOException("No space left on device")));

        duena.post("/api/v1/caja/sesiones", "{}");
        long stockAntes = movimientoRepository.stockDe(idLabial);

        Respuesta respuesta = duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":100000,\"lineas\":[{\"varianteId\":" + idLabial
                        + ",\"cantidad\":2}]}");

        Venta venta = ventaRepository.findAll().get(0);

        System.out.println("VERIFICACION cobro con el generador de recibos reventando => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        System.out.println("    stock " + stockAntes + " -> "
                + movimientoRepository.stockDe(idLabial)
                + " | movimientos de caja de la venta: "
                + movimientoCajaRepository.findByVentaId(venta.getId()).size()
                + " | ruta_recibo: " + venta.getRutaRecibo());

        // La venta existe, está cobrada y completa.
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("\"estado\":\"COMPLETADA\"");
        assertThat(venta.getEstado().name()).isEqualTo("COMPLETADA");
        assertThat(movimientoRepository.stockDe(idLabial)).isEqualTo(stockAntes - 2);
        assertThat(movimientoCajaRepository.findByVentaId(venta.getId())).hasSize(1);

        // Y lo que falta es solo el papel, que se puede rehacer.
        assertThat(venta.getRutaRecibo()).isNull();
        assertThat(respuesta.cuerpo()).contains("\"rutaRecibo\":null");
        assertThat(duena.get("/api/v1/ventas/sin-recibo").cuerpo())
                .contains("\"id\":" + venta.getId());
    }
}
