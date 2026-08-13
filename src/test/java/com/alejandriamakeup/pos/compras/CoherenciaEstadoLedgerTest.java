package com.alejandriamakeup.pos.compras;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
import com.alejandriamakeup.pos.inventario.MovimientoInventario;
import com.alejandriamakeup.pos.inventario.MovimientoInventarioRepository;
import com.alejandriamakeup.pos.inventario.TipoMovimientoInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El estado de una compra y el ledger no se pueden separar.
 *
 * <p>Este test es la contrapartida de una decisión de diseño. El estado se
 * <strong>guarda</strong> en {@code compra.estado} en vez de derivarse de los
 * movimientos, que sería la alternativa obvia y no podría quedar nunca
 * inconsistente. Guardarlo es mejor —un borrador y una descartada no tienen ningún
 * movimiento, así que derivar no podría distinguirlos, y el listado tendría que
 * recorrer el ledger entero para pintarse— pero acepta una deuda: ahora hay dos
 * fuentes que pueden discrepar. Esto es lo que la cobra.
 *
 * <p>Se recorren <strong>todas</strong> las compras de la base, no un caso armado:
 * un test sobre una compra concreta comprueba el camino que el autor tenía en la
 * cabeza, y lo que hace falta cubrir es el que no.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoherenciaEstadoLedgerTest {

    private static final String URL = BaseDatosAislada.urlNueva("coherencia-estado-ledger");

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
    @Autowired private CompraRepository compraRepository;
    @Autowired private MovimientoInventarioRepository movimientoRepository;

    private static Producto producto;
    private ClienteHttpDePrueba duena;
    private long idProveedor;

    @BeforeEach
    void entrar() {
        if (producto == null) {
            Usuario usuario = new Usuario();
            usuario.setNombre("Alejandra");
            usuario.setRol(Rol.DUENA);
            usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            usuario.setActivo(true);
            usuario.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(usuario);

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        idProveedor = idDe(duena.post("/api/v1/proveedores",
                "{\"nombre\":\"Proveedor " + java.util.UUID.randomUUID() + "\"}"));
    }

    @Test
    void elEstadoDeCadaCompraCuadraConLoQueDiceElLedger() {
        ejercitarLosCuatroEstados();

        List<Compra> compras = compraRepository.findAll();
        List<String> incoherencias = new ArrayList<>();
        Set<EstadoCompra> vistos = EnumSet.noneOf(EstadoCompra.class);

        for (Compra compra : compras) {
            vistos.add(compra.getEstado());

            List<MovimientoInventario> movimientos =
                    movimientoRepository.findByCompraId(compra.getId());
            boolean hayEntradas = movimientos.stream()
                    .anyMatch(m -> m.getTipo() == TipoMovimientoInventario.COMPRA);
            boolean hayAnulaciones = movimientos.stream()
                    .anyMatch(m -> m.getTipo() == TipoMovimientoInventario.ANULACION);

            switch (compra.getEstado()) {
                // Bicondicional: una compra anulada tiene sus movimientos de
                // anulacion, y unos movimientos de anulacion solo pueden colgar de
                // una compra anulada.
                case ANULADA -> {
                    if (!hayAnulaciones) {
                        incoherencias.add(compra.getConsecutivo()
                                + " está ANULADA pero no tiene movimientos de anulación");
                    }
                    if (!hayEntradas) {
                        incoherencias.add(compra.getConsecutivo()
                                + " está ANULADA pero nunca tuvo entradas: solo se anula lo recibido");
                    }
                }
                case RECIBIDA -> {
                    if (!hayEntradas) {
                        incoherencias.add(compra.getConsecutivo()
                                + " está RECIBIDA pero no tiene movimientos de entrada");
                    }
                    if (hayAnulaciones) {
                        incoherencias.add(compra.getConsecutivo()
                                + " está RECIBIDA pero tiene movimientos de anulación");
                    }
                }
                case BORRADOR, DESCARTADA -> {
                    if (!movimientos.isEmpty()) {
                        incoherencias.add(compra.getConsecutivo() + " está en "
                                + compra.getEstado() + " pero tiene " + movimientos.size()
                                + " movimiento(s): no debió tocar el inventario");
                    }
                }
            }
        }

        // El otro sentido del bicondicional no necesita su propio recorrido: como
        // aqui se visitan TODAS las compras y las tres ramas que no son ANULADA
        // exigen cero movimientos de anulacion, un movimiento de anulacion no puede
        // colgar de nada que no este ANULADA sin que este mismo bucle lo denuncie.
        // Recorrer el ledger por separado solo repetiria la afirmacion.

        System.out.println("VERIFICACION coherencia estado <-> ledger => " + compras.size()
                + " compra(s) revisadas, estados presentes " + vistos);

        assertThat(incoherencias)
                .withFailMessage("El estado guardado y el ledger dicen cosas distintas:%n%s",
                        String.join("\n", incoherencias))
                .isEmpty();

        // Un barrido sobre una poblacion pobre pasa por no tener nada que mirar.
        assertThat(vistos)
                .withFailMessage("El barrido no vio los cuatro estados, así que no probó gran cosa")
                .containsExactlyInAnyOrder(EstadoCompra.values());
    }

    /** Una compra de cada estado, por los caminos reales de la API. */
    private void ejercitarLosCuatroEstados() {
        long variante = nuevaVariante();

        crearBorrador(variante, 3);

        long recibida = crearBorrador(variante, 4);
        duena.post("/api/v1/compras/" + recibida + "/recepcion");

        long descartada = crearBorrador(variante, 5);
        duena.post("/api/v1/compras/" + descartada + "/descarte",
                "{\"motivo\":\"No la despacharon\"}");

        long anulada = crearBorrador(variante, 6);
        duena.post("/api/v1/compras/" + anulada + "/recepcion");
        duena.post("/api/v1/compras/" + anulada + "/anulacion",
                "{\"motivo\":\"Mercancia equivocada\"}");
    }

    private long crearBorrador(long varianteId, int cantidad) {
        return idDe(duena.post("/api/v1/compras", "{\"proveedorId\":" + idProveedor
                + ",\"lineas\":[{\"varianteId\":" + varianteId + ",\"cantidad\":" + cantidad
                + ",\"costoUnitario\":10000}]}"));
    }

    private long nuevaVariante() {
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Tono " + java.util.UUID.randomUUID(), null, null,
                30_000L, 0, null, null)).getId();
    }

    private long idDe(Respuesta respuesta) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(respuesta.cuerpo());
        if (!buscador.find()) {
            throw new IllegalStateException("Sin id en " + respuesta.cuerpo());
        }
        return Long.parseLong(buscador.group(1));
    }
}
