package com.alejandriamakeup.pos.seguridad;

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
import com.alejandriamakeup.pos.catalogo.Variante;
import com.alejandriamakeup.pos.catalogo.dto.PeticionesCatalogo;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Los 403 que quedaron diferidos en la Fase 2.
 *
 * <p>Entonces sus módulos no existían y no se podía pedir un 403 a un endpoint
 * inexistente: quedaron cubiertos por tests unitarios sobre {@link PermisosPorRol}, con
 * la deuda anotada. Ahora el catálogo y el inventario existen, así que la deuda se
 * paga por HTTP, que es donde la EMPLEADA va a golpear de verdad.
 *
 * <p>Queda diferido solo <strong>anular ventas</strong>, que sigue sin módulo.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PermisosCatalogoHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("permisos-catalogo");

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

    private static Long idVariante;
    private static Long idProducto;

    private ClienteHttpDePrueba empleada;

    @BeforeEach
    void prepararCatalogoYEntrarComoEmpleada() {
        if (idVariante == null) {
            crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");

            Marca marca = servicioMarca.crear("Maybelline");
            Categoria categoria = servicioCategoria.crear("Labios");
            Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                    "Labial mate", marca.getId(), categoria.getId(), null));
            Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                    producto.getId(), "Rojo", "5 ml", null, 38_900L, 3, null, null));

            idProducto = producto.getId();
            idVariante = variante.getId();
        }

        empleada = new ClienteHttpDePrueba(puerto);
        assertThat(empleada.post("/api/v1/auth/login",
                "{\"nombre\":\"Camila\",\"pin\":\"2222\"}").estado()).isEqualTo(200);
    }

    @Test
    void laEmpleadaNoPuedeEditarPrecios() {
        Respuesta respuesta = empleada.put("/api/v1/catalogo/variantes/" + idVariante,
                "{\"productoId\":" + idProducto + ",\"tono\":\"Rojo\",\"precioVenta\":1}");

        System.out.println("VERIFICACION EMPLEADA edita precio => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("EDITAR_CATALOGO");
    }

    @Test
    void laEmpleadaNoPuedeCrearNiModificarElCatalogo() {
        record Caso(String descripcion, Respuesta respuesta) {
        }

        var casos = java.util.List.of(
                new Caso("crear marca", empleada.post("/api/v1/catalogo/marcas", "{\"nombre\":\"Nueva\"}")),
                new Caso("crear categoría",
                        empleada.post("/api/v1/catalogo/categorias", "{\"nombre\":\"Nueva\"}")),
                new Caso("crear producto", empleada.post("/api/v1/catalogo/productos",
                        "{\"nombre\":\"X\",\"marcaId\":1,\"categoriaId\":1}")),
                new Caso("crear variante", empleada.post("/api/v1/catalogo/variantes",
                        "{\"productoId\":" + idProducto + ",\"precioVenta\":100}")),
                new Caso("desactivar variante",
                        empleada.post("/api/v1/catalogo/variantes/" + idVariante + "/desactivacion")),
                new Caso("desactivar producto",
                        empleada.post("/api/v1/catalogo/productos/" + idProducto + "/desactivacion")));

        for (Caso caso : casos) {
            System.out.println("VERIFICACION EMPLEADA " + caso.descripcion() + " => "
                    + caso.respuesta().estado());
            assertThat(caso.respuesta().estado())
                    .withFailMessage("La EMPLEADA pudo %s: llegó %d", caso.descripcion(),
                            caso.respuesta().estado())
                    .isEqualTo(403);
        }
    }

    @Test
    void laEmpleadaNoPuedeVerCostosNiMargenes() {
        Respuesta respuesta = empleada.get("/api/v1/catalogo/costos");

        System.out.println("VERIFICACION EMPLEADA pide costos => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("VER_COSTOS_Y_MARGENES");
        assertThat(respuesta.cuerpo()).doesNotContain("costoPromedio").doesNotContain("margen");
    }

    @Test
    void laEmpleadaNoPuedeAjustarInventario() {
        Respuesta respuesta = empleada.post("/api/v1/inventario/ajustes",
                "{\"varianteId\":" + idVariante + ",\"cantidad\":-5,\"motivo\":\"me faltan\"}");

        System.out.println("VERIFICACION EMPLEADA ajusta inventario => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("AJUSTAR_INVENTARIO");
    }

    @Test
    void laEmpleadaNoPuedeCargarInventarioInicial() {
        Respuesta respuesta = empleada.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + idVariante + ",\"cantidad\":100,\"costoUnitario\":1}]}");

        System.out.println("VERIFICACION EMPLEADA carga inventario inicial => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("CARGAR_INVENTARIO_INICIAL");
    }

    /** Lo que sí puede: leer el catálogo, que es su trabajo en el mostrador. */
    @Test
    void laEmpleadaSiPuedeLeerElCatalogo() {
        Respuesta respuesta = empleada.get("/api/v1/catalogo");

        System.out.println("VERIFICACION EMPLEADA lee el catálogo => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("Labial mate").contains("38900");
    }

    private void crear(String nombre, Rol rol, String pin) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setRol(rol);
        usuario.setPinHash(new BCryptPasswordEncoder().encode(pin));
        usuario.setActivo(true);
        usuario.setFechaCreacion(Fechas.ahora());
        usuarioRepository.save(usuario);
    }
}
