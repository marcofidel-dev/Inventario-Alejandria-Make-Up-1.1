package com.alejandriamakeup.pos.compras;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El ciclo de una compra por HTTP: borrador, edición, descarte y las transiciones
 * que no se permiten.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ComprasHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("compras-http");

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

    private static Long idVarianteUna;
    private static Long idVarianteOtra;
    private ClienteHttpDePrueba duena;
    private long idProveedor;

    @BeforeEach
    void entrar() {
        if (idVarianteUna == null) {
            Usuario usuario = new Usuario();
            usuario.setNombre("Alejandra");
            usuario.setRol(Rol.DUENA);
            usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            usuario.setActivo(true);
            usuario.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(usuario);

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));
            idVarianteUna = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", null, null, 30_000L, 0, null, null)).getId();
            idVarianteOtra = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Nude", null, null, 25_000L, 0, null, null)).getId();
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        idProveedor = crearProveedor("Proveedor " + java.util.UUID.randomUUID());
    }

    /**
     * <strong>El total lo calcula el servidor.</strong>
     *
     * <p>La petición no tiene siquiera un campo donde mandarlo, y este test fija la
     * consecuencia: 3 × 12.000 + 2 × 8.000 = 52.000, y ese es el número que sale en la
     * respuesta. Es el mismo que la pantalla vuelve a mostrar después de guardar, en
     * vez del que venía sumando mientras se tecleaba.
     */
    @Test
    void elTotalDelBorradorLoCalculaElServidorSumandoLasLineas() {
        Respuesta respuesta = crearBorrador(idProveedor,
                linea(idVarianteUna, 3, 12_000), linea(idVarianteOtra, 2, 8_000));

        System.out.println("VERIFICACION total calculado por el servidor => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo())
                .contains("\"total\":52000")
                .contains("\"subtotal\":36000")
                .contains("\"subtotal\":16000")
                .contains("\"estado\":\"BORRADOR\"");
    }

    /** El consecutivo sale de la tabla, con su prefijo y sin huecos. */
    @Test
    void elBorradorRecibeSuConsecutivoDeLaSerieDeCompras() {
        Respuesta respuesta = crearBorrador(idProveedor, linea(idVarianteUna, 1, 5_000));

        System.out.println("VERIFICACION consecutivo => " + extraer(respuesta.cuerpo(), "consecutivo"));
        assertThat(respuesta.cuerpo()).containsPattern("\"consecutivo\":\"C-\\d{6}\"");
    }

    @Test
    void editarUnBorradorReemplazaSusLineasYRecalculaElTotal() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 3, 12_000)));

        Respuesta respuesta = duena.put("/api/v1/compras/" + id,
                cuerpoCompra(idProveedor, linea(idVarianteUna, 1, 1_000)));

        System.out.println("VERIFICACION borrador editado => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"total\":1000");
        // La linea vieja no quedo colgando: si quedara, el total seria 37000.
        assertThat(respuesta.cuerpo()).doesNotContain("\"subtotal\":36000");
    }

    /**
     * Descartar un borrador exige motivo, deja constancia y no toca inventario.
     */
    @Test
    void descartarUnBorradorLoDejaConSuMotivo() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 2, 4_000)));

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/descarte",
                "{\"motivo\":\"El proveedor cancelo el despacho\"}");

        System.out.println("VERIFICACION descarte => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo())
                .contains("\"estado\":\"DESCARTADA\"")
                .contains("\"motivoBaja\":\"El proveedor cancelo el despacho\"")
                .contains("\"fechaBaja\":");
    }

    /** Sin motivo no hay descarte: lo exige el DTO y también el CHECK de la V5. */
    @Test
    void descartarSinMotivoNoSePuede() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 2, 4_000)));

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/descarte", "{\"motivo\":\"  \"}");

        System.out.println("VERIFICACION descarte sin motivo => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"VALIDACION_FALLIDA\"");
    }

    /**
     * Una compra recibida ya no se edita, y el mensaje lo dice. Es lo que la pantalla
     * advierte antes de confirmar la recepción.
     */
    @Test
    void unaCompraRecibidaYaNoSeEdita() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 2, 4_000)));
        duena.post("/api/v1/compras/" + id + "/recepcion");

        Respuesta respuesta = duena.put("/api/v1/compras/" + id,
                cuerpoCompra(idProveedor, linea(idVarianteUna, 9, 9_000)));

        System.out.println("VERIFICACION editar una recibida => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"ESTADO_DE_COMPRA_INVALIDO\"");
    }

    /** Descartar solo aplica a borradores: una recibida se anula, que es otra cosa. */
    @Test
    void unaCompraRecibidaNoSeDescarta() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 2, 4_000)));
        duena.post("/api/v1/compras/" + id + "/recepcion");

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/descarte",
                "{\"motivo\":\"me equivoque\"}");

        System.out.println("VERIFICACION descartar una recibida => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("se anula");
    }

    /** Un borrador no se anula: no hay nada que devolver. */
    @Test
    void unBorradorNoSeAnula() {
        long id = idDe(crearBorrador(idProveedor, linea(idVarianteUna, 2, 4_000)));

        Respuesta respuesta = duena.post("/api/v1/compras/" + id + "/anulacion",
                "{\"motivo\":\"me equivoque\"}");

        System.out.println("VERIFICACION anular un borrador => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("se descarta");
    }

    /** Una compra sin líneas ni siquiera se puede crear: el lote llega completo. */
    @Test
    void unaCompraSinLineasNoSeCrea() {
        Respuesta respuesta = duena.post("/api/v1/compras",
                "{\"proveedorId\":" + idProveedor + ",\"lineas\":[]}");

        System.out.println("VERIFICACION compra sin lineas => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("al menos una línea");
    }

    /** A un proveedor desactivado no se le registran compras nuevas. */
    @Test
    void unProveedorDesactivadoNoRecibeComprasNuevas() {
        long id = crearProveedor("Descontinuado " + java.util.UUID.randomUUID());
        duena.post("/api/v1/proveedores/" + id + "/desactivacion");

        Respuesta respuesta = crearBorrador(id, linea(idVarianteUna, 1, 1_000));

        System.out.println("VERIFICACION compra a proveedor inactivo => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"PROVEEDOR_INACTIVO\"");
    }

    // ------------------------------------------------------------- utilidades

    private long crearProveedor(String nombre) {
        return idDe(duena.post("/api/v1/proveedores", "{\"nombre\":\"" + nombre + "\"}"));
    }

    private Respuesta crearBorrador(long proveedorId, String... lineas) {
        return duena.post("/api/v1/compras", cuerpoCompra(proveedorId, lineas));
    }

    private String cuerpoCompra(long proveedorId, String... lineas) {
        return "{\"proveedorId\":" + proveedorId + ",\"lineas\":[" + String.join(",", lineas) + "]}";
    }

    private String linea(long varianteId, int cantidad, long costo) {
        return "{\"varianteId\":" + varianteId + ",\"cantidad\":" + cantidad
                + ",\"costoUnitario\":" + costo + "}";
    }

    private long idDe(Respuesta respuesta) {
        return Long.parseLong(extraer(respuesta.cuerpo(), "id"));
    }

    private String extraer(String json, String campo) {
        var buscador = java.util.regex.Pattern
                .compile("\"" + campo + "\":\"?([^,\"}]+)\"?").matcher(json);
        if (!buscador.find()) {
            throw new IllegalStateException("No se encontró " + campo + " en " + json);
        }
        return buscador.group(1);
    }
}
