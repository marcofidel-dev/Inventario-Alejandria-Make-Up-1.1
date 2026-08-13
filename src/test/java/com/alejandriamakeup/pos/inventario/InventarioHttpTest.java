package com.alejandriamakeup.pos.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

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
import com.alejandriamakeup.pos.catalogo.VarianteRepository;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Carga inicial y ajustes: las dos puertas por las que entra inventario que no viene
 * de una compra.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InventarioHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("inventario");

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
    private VarianteRepository varianteRepository;

    @Autowired
    private MovimientoInventarioRepository movimientoRepository;

    private static Long usuarioId;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void entrarComoDuena() {
        if (usuarioId == null) {
            Usuario alejandra = new Usuario();
            alejandra.setNombre("Alejandra");
            alejandra.setRol(Rol.DUENA);
            alejandra.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            alejandra.setActivo(true);
            alejandra.setFechaCreacion(Fechas.ahora());
            usuarioId = usuarioRepository.save(alejandra).getId();
        }
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    // ------------------------------------------------------- carga inicial

    @Test
    void laCargaInicialCreaElMovimientoYFijaElCostoPromedio() {
        Variante variante = nuevaVariante();

        Respuesta respuesta = duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":24,\"costoUnitario\":21000}]}");

        System.out.println("VERIFICACION carga inicial => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("CARGA_INICIAL").contains("\"variantesCargadas\":1");

        assertThat(movimientoRepository.stockDe(variante.getId())).isEqualTo(24);
        assertThat(varianteRepository.findById(variante.getId()).orElseThrow().getCostoPromedio())
                .isEqualTo(21_000);
    }

    /**
     * Una variante admite una sola carga inicial. La segunda vez ya no se está
     * declarando lo que había, se está corrigiendo lo que se contó — y eso es un ajuste,
     * que deja constancia del motivo.
     */
    @Test
    void unaVarianteSoloAdmiteUnaCargaInicial() {
        Variante variante = nuevaVariante();
        String cuerpo = "{\"lineas\":[{\"varianteId\":" + variante.getId()
                + ",\"cantidad\":10,\"costoUnitario\":5000}]}";

        assertThat(duena.post("/api/v1/inventario/carga-inicial", cuerpo).estado()).isEqualTo(201);
        Respuesta segunda = duena.post("/api/v1/inventario/carga-inicial", cuerpo);

        System.out.println("VERIFICACION segunda carga inicial => " + segunda.estado()
                + " " + segunda.cuerpo());
        assertThat(segunda.estado()).isEqualTo(409);
        assertThat(segunda.cuerpo()).contains("CARGA_INICIAL_YA_REGISTRADA").contains("por ajuste");
        // Y no duplicó el stock.
        assertThat(movimientoRepository.stockDe(variante.getId())).isEqualTo(10);
    }

    @Test
    void aguantaUnLoteGrande() {
        List<Variante> variantes = IntStream.range(0, 40).mapToObj(i -> nuevaVariante()).toList();

        StringBuilder lineas = new StringBuilder();
        for (Variante v : variantes) {
            lineas.append(lineas.isEmpty() ? "" : ",")
                  .append("{\"varianteId\":").append(v.getId())
                  .append(",\"cantidad\":6,\"costoUnitario\":8000}");
        }

        Respuesta respuesta = duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[" + lineas + "]}");

        System.out.println("VERIFICACION lote de 40 variantes => " + respuesta.estado()
                + ", variantesCargadas en la respuesta: "
                + respuesta.cuerpo().contains("\"variantesCargadas\":40"));
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo()).contains("\"variantesCargadas\":40");
        variantes.forEach(v -> assertThat(movimientoRepository.stockDe(v.getId())).isEqualTo(6));
    }

    /**
     * Todo o nada. Si la línea del medio está mal, no puede quedar medio inventario
     * cargado: quien lo estaba metiendo no sabría por dónde se quedó, y volver a
     * mandarlo entero chocaría con las cargas iniciales ya hechas.
     */
    @Test
    void siUnaLineaFallaNoQuedaNadaCargado() {
        Variante buena1 = nuevaVariante();
        Variante yaCargada = nuevaVariante();
        Variante buena2 = nuevaVariante();

        // Esta ya tiene su carga inicial, así que romperá el lote por el medio.
        servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(yaCargada.getId(), 3, 1000L))), usuarioId);

        var lote = new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(buena1.getId(), 5, 2000L),
                new PeticionesInventario.CargaInicial.Linea(yaCargada.getId(), 5, 2000L),
                new PeticionesInventario.CargaInicial.Linea(buena2.getId(), 5, 2000L)));

        assertThatThrownBy(() -> servicioInventario.cargaInicial(lote, usuarioId))
                .isInstanceOf(ErrorDeAplicacion.class)
                .hasMessageContaining("ya tiene carga inicial");

        System.out.println("VERIFICACION tras el lote fallido => stock de la primera: "
                + movimientoRepository.stockDe(buena1.getId()) + ", de la tercera: "
                + movimientoRepository.stockDe(buena2.getId()));

        assertThat(movimientoRepository.stockDe(buena1.getId()))
                .withFailMessage("La línea anterior al fallo quedó grabada: el lote no es atómico")
                .isZero();
        assertThat(movimientoRepository.stockDe(buena2.getId())).isZero();
        assertThat(varianteRepository.findById(buena1.getId()).orElseThrow().getCostoPromedio())
                .isZero();
    }

    @Test
    void unaVarianteRepetidaEnElMismoLoteSeRechaza() {
        Variante variante = nuevaVariante();

        Respuesta respuesta = duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + variante.getId() + ",\"cantidad\":5,\"costoUnitario\":100},"
                        + "{\"varianteId\":" + variante.getId() + ",\"cantidad\":5,\"costoUnitario\":100}]}");

        System.out.println("VERIFICACION variante repetida en el lote => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("repetida en el lote");
        assertThat(movimientoRepository.stockDe(variante.getId())).isZero();
    }

    @Test
    void unaCargaInicialDeCeroNoTieneSentido() {
        Variante variante = nuevaVariante();

        Respuesta respuesta = duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":0,\"costoUnitario\":100}]}");

        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("cantidad");
    }

    // -------------------------------------------------------------- ajustes

    @Test
    void unAjusteExigeMotivoYMueveElStock() {
        Variante variante = nuevaVariante();
        duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":20,\"costoUnitario\":15000}]}");

        Respuesta sinMotivo = duena.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + variante.getId() + ",\"cantidad\":-3,\"motivo\":\"\"}");
        System.out.println("VERIFICACION ajuste sin motivo => " + sinMotivo.estado()
                + " " + sinMotivo.cuerpo());
        assertThat(sinMotivo.estado()).isEqualTo(400);
        assertThat(sinMotivo.cuerpo()).contains("motivo");

        Respuesta conMotivo = duena.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":-3,\"motivo\":\"tres se rompieron en la vitrina\"}");
        System.out.println("VERIFICACION ajuste de -3 => " + conMotivo.estado() + " " + conMotivo.cuerpo());
        assertThat(conMotivo.estado()).isEqualTo(201);
        assertThat(conMotivo.cuerpo()).contains("AJUSTE").contains("\"stockResultante\":17");
        assertThat(movimientoRepository.stockDe(variante.getId())).isEqualTo(17);
    }

    /**
     * El ajuste copia el costo promedio vigente. Si no lo hiciera, una merma restaría
     * unidades del stock pero cero del valor del inventario, y el inventario valorado se
     * iría separando de la realidad sin que nada lo delatara.
     */
    @Test
    void elAjusteLlevaSuPropiaValoracion() {
        Variante variante = nuevaVariante();
        duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":10,\"costoUnitario\":13500}]}");

        Respuesta ajuste = duena.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + variante.getId() + ",\"cantidad\":-2,\"motivo\":\"merma\"}");

        System.out.println("VERIFICACION costo del movimiento de ajuste => " + ajuste.cuerpo());
        assertThat(ajuste.cuerpo()).contains("\"costoUnitario\":13500");
    }

    @Test
    void unAjusteDeCeroNoAjustaNada() {
        Variante variante = nuevaVariante();

        Respuesta respuesta = duena.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + variante.getId() + ",\"cantidad\":0,\"motivo\":\"nada\"}");

        System.out.println("VERIFICACION ajuste de 0 => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("no ajusta nada");
    }

    @Test
    void unAjustePositivoTambienSePuede() {
        Variante variante = nuevaVariante();

        Respuesta respuesta = duena.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + variante.getId()
                        + ",\"cantidad\":4,\"motivo\":\"aparecieron cuatro en la bodega\"}");

        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(movimientoRepository.stockDe(variante.getId())).isEqualTo(4);
    }

    private Variante nuevaVariante() {
        String sufijo = String.valueOf(System.nanoTime());
        Marca marca = servicioMarca.crear("Marca " + sufijo);
        Categoria categoria = servicioCategoria.crear("Categoría " + sufijo);
        Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Producto " + sufijo, marca.getId(), categoria.getId(), null));
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Tono " + sufijo, "5 ml", null, 35_000L, 2, null, null));
    }
}
