package com.alejandriamakeup.pos.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.alejandriamakeup.pos.catalogo.ServicioCatalogoCompleto;
import com.alejandriamakeup.pos.catalogo.ServicioCategoria;
import com.alejandriamakeup.pos.catalogo.ServicioMarca;
import com.alejandriamakeup.pos.catalogo.ServicioProducto;
import com.alejandriamakeup.pos.catalogo.ServicioVariante;
import com.alejandriamakeup.pos.catalogo.dto.CatalogoDto;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.compras.ServicioCompra;
import com.alejandriamakeup.pos.compras.ServicioProveedor;
import com.alejandriamakeup.pos.compras.ServicioRecepcion;
import com.alejandriamakeup.pos.compras.dto.CompraDto;
import com.alejandriamakeup.pos.compras.dto.PeticionesCompras;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * La regla «sin costo no se vende» está escrita dos veces. Aquí se exige que digan
 * lo mismo.
 *
 * <p>Una está en SQL, dentro de {@code VarianteRepository.filas()}, y produce la
 * bandera {@code sinCosto} que el catálogo le manda al punto de venta. La otra está
 * en Java, en {@code ServicioVenta.exigirCosto}, y es la que rechaza el cobro con
 * {@code VARIANTE_SIN_COSTO}.
 *
 * <p>Si divergen, el daño es exactamente el que la Fase 9 quiso evitar: la pantalla
 * deja entrar al carrito algo que el cobro va a rechazar, y el rechazo aparece con
 * el carrito lleno y una clienta enfrente — que es el sitio donde se dijo que no
 * podía aparecer. Al revés es peor de otra manera: la pantalla bloquearía un
 * producto que sí se puede vender.
 *
 * <p>Por eso no se comprueban casos elegidos a mano, sino <strong>los dos conjuntos
 * completos</strong>: se pide el catálogo, se intenta cobrar cada variante que
 * existe, y se exige la igualdad. Un caso nuevo que alguien agregue al fixture entra
 * solo en la comparación.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoherenciaSinCostoTest {

    private static final String URL = BaseDatosAislada.urlNueva("coherencia-sin-costo");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    private static final long PRECIO = 38_900;
    private static final long COSTO = 20_100;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ServicioMarca servicioMarca;
    @Autowired private ServicioCategoria servicioCategoria;
    @Autowired private ServicioProducto servicioProducto;
    @Autowired private ServicioVariante servicioVariante;
    @Autowired private ServicioInventario servicioInventario;
    @Autowired private ServicioProveedor servicioProveedor;
    @Autowired private ServicioCompra servicioCompra;
    @Autowired private ServicioRecepcion servicioRecepcion;
    @Autowired private ServicioCatalogoCompleto servicioCatalogo;

    private static Long conCosto;
    private static Long nuncaRecibida;
    private static Long compraAnulada;
    private static Long inactivaConCosto;

    private ClienteHttpDePrueba duena;

    /**
     * Los cuatro mundos en que una variante puede estar respecto del costo. Los dos
     * del medio son los que separan {@code sinCosto} de las banderas con las que se
     * confunde: {@code compraAnulada} tiene historial y no tiene costo, e
     * {@code inactivaConCosto} está desactivada y sí se puede despachar.
     */
    @BeforeEach
    void sembrarYEntrar() {
        if (conCosto == null) {
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

            conCosto = nuevaVariante(producto, "Rojo");
            nuncaRecibida = nuevaVariante(producto, "Coral");
            compraAnulada = nuevaVariante(producto, "Vino");
            inactivaConCosto = nuevaVariante(producto, "Nude");

            servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                    new PeticionesInventario.CargaInicial.Linea(conCosto, 10, COSTO),
                    new PeticionesInventario.CargaInicial.Linea(inactivaConCosto, 10, COSTO))),
                    idDuena);

            servicioVariante.cambiarActivo(inactivaConCosto, false);

            // Recibida y después anulada: el replay salta sus dos movimientos y el
            // promedio vuelve a cero. Tiene historial y no tiene costo, que es
            // justamente el caso donde conHistorial no alcanza.
            var proveedor = servicioProveedor.crear(new PeticionesCompras.Proveedor(
                    "Distribuciones López", null, null, null, null));
            CompraDto compra = servicioCompra.crearBorrador(new PeticionesCompras.Compra(
                    proveedor.getId(), "F-1", null,
                    List.of(new PeticionesCompras.Compra.Linea(compraAnulada, 5, COSTO))),
                    idDuena);
            servicioRecepcion.recibir(compra.id(), idDuena);
            servicioRecepcion.anular(compra.id(), "la mercancía nunca llegó", idDuena);
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void elCatalogoMarcaSinCostoExactamenteLoQueElCobroRechaza() {
        // Sin caja abierta TODO daría 409 SIN_SESION_ABIERTA y los dos conjuntos
        // saldrían vacíos: el test pasaría sin haber comparado nada.
        duena.post("/api/v1/caja/sesiones", "{}");

        List<CatalogoDto.VarianteDto> variantes = servicioCatalogo.completo().variantes();

        Set<Long> marcadasPorElCatalogo = variantes.stream()
                .filter(CatalogoDto.VarianteDto::sinCosto)
                .map(CatalogoDto.VarianteDto::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> rechazadasPorElCobro = new LinkedHashSet<>();
        Set<Long> cobradas = new LinkedHashSet<>();

        System.out.println("VERIFICACION cobrando una a una las " + variantes.size()
                + " variantes del catálogo:");

        for (CatalogoDto.VarianteDto variante : variantes) {
            Respuesta respuesta = duena.post("/api/v1/ventas",
                    "{\"uuid\":\"" + UUID.randomUUID() + "\",\"metodoPago\":\"TARJETA\","
                            + "\"lineas\":[{\"varianteId\":" + variante.id()
                            + ",\"cantidad\":1}]}");

            boolean sinCosto = respuesta.estado() == 409
                    && respuesta.cuerpo().contains("VARIANTE_SIN_COSTO");
            if (sinCosto) {
                rechazadasPorElCobro.add(variante.id());
            } else if (respuesta.estado() == 201) {
                cobradas.add(variante.id());
            }

            System.out.println("    variante " + variante.id() + " => catálogo sinCosto="
                    + variante.sinCosto() + " | cobro " + respuesta.estado()
                    + (sinCosto ? " VARIANTE_SIN_COSTO" : ""));
        }

        assertThat(rechazadasPorElCobro)
                .withFailMessage("El catálogo marca sinCosto en %s y el cobro rechaza %s. "
                        + "La regla está escrita en SQL y en Java y las dos versiones "
                        + "discrepan: la pantalla dejaría entrar al carrito algo que el "
                        + "cobro rechaza, o bloquearía algo que sí se puede vender.",
                        marcadasPorElCatalogo, rechazadasPorElCobro)
                .isEqualTo(marcadasPorElCatalogo);

        // Las dos contrapartes, sin las cuales dos conjuntos vacíos pasarían el test.
        assertThat(marcadasPorElCatalogo).containsExactlyInAnyOrder(nuncaRecibida, compraAnulada);
        assertThat(cobradas).containsExactlyInAnyOrder(conCosto, inactivaConCosto);
    }

    private Long nuevaVariante(Producto producto, String tono) {
        return servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), tono, "5 ml", null, PRECIO, 2, null, null)).getId();
    }
}
