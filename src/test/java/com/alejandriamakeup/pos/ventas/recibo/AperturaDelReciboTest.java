package com.alejandriamakeup.pos.ventas.recibo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * <strong>El recibo se abre en el visor del sistema, y eso es cosa del backend.</strong>
 *
 * <p>La aplicación corre dentro de un navegador en modo app, sin barra de direcciones.
 * Ahí el PDF servido por HTTP abriría una ventana de navegador suelta encima de la
 * pantalla de cobro, con la clienta enfrente y alguien buscando cómo cerrarla. El único
 * que puede hablar con el escritorio es el servidor, así que la apertura es un endpoint.
 *
 * <p>{@link AbridorDelSistema} va mockeado porque la alternativa sería abrir un visor de
 * PDF de verdad en la máquina que corre las pruebas — y en el servidor de integración no
 * hay escritorio ninguno. Lo que se comprueba es el contrato: qué archivo recibe, que no
 * se le pida abrir nada cuando no hay recibo, y qué se responde cuando el equipo no
 * tiene con qué abrirlo.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AperturaDelReciboTest {

    private static final String URL = BaseDatosAislada.urlNueva("apertura-del-recibo");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @MockitoBean
    private AbridorDelSistema abridor;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private VentaRepository ventaRepository;

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

    private long cobrar() {
        duena.post("/api/v1/caja/sesiones", "{\"baseInicial\":100000}");
        duena.post("/api/v1/ventas",
                "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"EFECTIVO\","
                        + "\"efectivoRecibido\":100000,\"lineas\":[{\"varianteId\":" + idLabial
                        + ",\"cantidad\":1}]}");
        List<Venta> ventas = ventaRepository.findAll();
        return ventas.get(ventas.size() - 1).getId();
    }

    @Test
    void abreElArchivoDelReciboRecienCobrado() {
        long ventaId = cobrar();

        Respuesta respuesta = duena.post("/api/v1/ventas/" + ventaId + "/recibo/apertura", "");

        ArgumentCaptor<Path> abierto = ArgumentCaptor.forClass(Path.class);
        verify(abridor).abrir(abierto.capture());

        System.out.println("VERIFICACION apertura del recibo => " + respuesta.estado()
                + " archivo=" + abierto.getValue());

        // Sin cuerpo: no hay nada que decirle a la pantalla que no sepa ya.
        assertThat(respuesta.estado()).isEqualTo(204);

        // El archivo que se abre es el PDF de ESTA venta, no una ruta armada a mano.
        Venta venta = ventaRepository.findById(ventaId).orElseThrow();
        assertThat(abierto.getValue().toString())
                .endsWith(venta.getConsecutivo() + ".pdf")
                .contains("recibos");
        assertThat(abierto.getValue()).exists();
    }

    /**
     * Sin recibo generado no se le pide al escritorio que abra nada: se responde 404 con
     * un mensaje que lleva a regenerarlo. Pasarle una ruta inexistente a
     * {@code Desktop.open} produce un error del sistema operativo, que en pantalla no
     * dice qué hacer.
     */
    @Test
    void sinReciboNoLePideAlEscritorioQueAbraNada() {
        long ventaId = cobrar();
        Venta venta = ventaRepository.findById(ventaId).orElseThrow();
        venta.setRutaRecibo(null);
        ventaRepository.save(venta);

        Respuesta respuesta = duena.post("/api/v1/ventas/" + ventaId + "/recibo/apertura", "");

        System.out.println("VERIFICACION apertura sin recibo => " + respuesta.estado()
                + " " + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(404);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"NO_ENCONTRADO\"");
        assertThat(respuesta.cuerpo()).contains("volver a generar");
        verify(abridor, never()).abrir(any());
    }

    /**
     * Un equipo sin visor de PDF asociado responde con código propio y diciendo dónde
     * quedó el archivo. La forma del error es la de siempre —{@code {codigo, error}}—
     * para que la pantalla pueda ramificar sobre el código y mostrar el mensaje tal cual.
     */
    @Test
    void sinVisorLoDiceYNombraElArchivo() {
        long ventaId = cobrar();
        doThrow(ErrorDeAplicacion.conflicto("SIN_VISOR",
                "Este equipo no tiene con qué abrir el PDF. El archivo está en C:/x.pdf "
                        + "y se puede abrir a mano."))
                .when(abridor).abrir(any());

        Respuesta respuesta = duena.post("/api/v1/ventas/" + ventaId + "/recibo/apertura", "");

        System.out.println("VERIFICACION apertura sin visor => " + respuesta.estado()
                + " " + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"SIN_VISOR\"");
        assertThat(respuesta.cuerpo()).contains("se puede abrir a mano");
    }
}
