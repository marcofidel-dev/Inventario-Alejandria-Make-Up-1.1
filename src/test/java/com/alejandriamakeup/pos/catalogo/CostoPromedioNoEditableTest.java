package com.alejandriamakeup.pos.catalogo;

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
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El costo promedio no se dicta desde afuera, ni siquiera siendo la DUENA.
 *
 * <p>Se deriva de la carga inicial y de la recepción de compras. Si se pudiera editar a
 * mano, el margen de cualquier informe dejaría de significar algo: sería el margen que
 * alguien tecleó, no el que la mercancía costó.
 *
 * <p>El mecanismo es que los DTO de entrada no tienen el campo, y Jackson ignora las
 * propiedades desconocidas. Eso último es configuración por defecto de Spring Boot y
 * podría cambiar, así que este test lo comprueba en vez de confiar en ello: manda
 * {@code costoPromedio} en el cuerpo y verifica que el valor guardado no se movió.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CostoPromedioNoEditableTest {

    private static final String URL = BaseDatosAislada.urlNueva("costo-no-editable");
    private static final long COSTO_REAL = 21_000;
    private static final long COSTO_INVENTADO = 999_999;

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private VarianteRepository varianteRepository;

    private ClienteHttpDePrueba duena;

    @BeforeEach
    void entrarComoDuena() {
        if (usuarioRepository.count() == 0) {
            Usuario alejandra = new Usuario();
            alejandra.setNombre("Alejandra");
            alejandra.setRol(Rol.DUENA);
            alejandra.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            alejandra.setActivo(true);
            alejandra.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(alejandra);
        }
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void mandarCostoPromedioAlCrearNoLoFija() {
        long productoId = crearProducto();

        Respuesta creacion = duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Al crear\",\"precioVenta\":38900,"
                        + "\"costoPromedio\":" + COSTO_INVENTADO + "}");

        long varianteId = idDe(creacion);
        long guardado = varianteRepository.findById(varianteId).orElseThrow().getCostoPromedio();

        System.out.println("VERIFICACION mandé costoPromedio=" + COSTO_INVENTADO
                + " al crear => guardado: " + guardado);
        assertThat(creacion.estado()).isEqualTo(201);
        assertThat(guardado).isZero();
        assertThat(creacion.cuerpo()).doesNotContain(String.valueOf(COSTO_INVENTADO));
    }

    @Test
    void mandarCostoPromedioAlEditarNoLoCambia() {
        long productoId = crearProducto();
        long varianteId = idDe(duena.post("/api/v1/catalogo/variantes",
                "{\"productoId\":" + productoId + ",\"tono\":\"Al editar\",\"precioVenta\":38900}"));

        // El unico camino legitimo: la carga inicial lo fija.
        duena.post("/api/v1/inventario/carga-inicial",
                "{\"lineas\":[{\"varianteId\":" + varianteId + ",\"cantidad\":5,"
                        + "\"costoUnitario\":" + COSTO_REAL + "}]}");
        assertThat(varianteRepository.findById(varianteId).orElseThrow().getCostoPromedio())
                .isEqualTo(COSTO_REAL);

        Respuesta edicion = duena.put("/api/v1/catalogo/variantes/" + varianteId,
                "{\"productoId\":" + productoId + ",\"tono\":\"Al editar\",\"precioVenta\":45000,"
                        + "\"costoPromedio\":" + COSTO_INVENTADO + "}");

        long despues = varianteRepository.findById(varianteId).orElseThrow().getCostoPromedio();
        System.out.println("VERIFICACION costo real " + COSTO_REAL + ", mandé "
                + COSTO_INVENTADO + " al editar => quedó en " + despues);

        assertThat(edicion.estado()).isEqualTo(200);
        assertThat(despues).isEqualTo(COSTO_REAL);
        // El precio sí cambió: la petición no se ignoró entera, solo el campo prohibido.
        assertThat(varianteRepository.findById(varianteId).orElseThrow().getPrecioVenta())
                .isEqualTo(45_000);
    }

    private long crearProducto() {
        String sufijo = String.valueOf(System.nanoTime());
        long marcaId = idDe(duena.post("/api/v1/catalogo/marcas", "{\"nombre\":\"Marca " + sufijo + "\"}"));
        long categoriaId = idDe(duena.post("/api/v1/catalogo/categorias",
                "{\"nombre\":\"Categoría " + sufijo + "\"}"));
        return idDe(duena.post("/api/v1/catalogo/productos",
                "{\"nombre\":\"Producto " + sufijo + "\",\"marcaId\":" + marcaId
                        + ",\"categoriaId\":" + categoriaId + "}"));
    }

    private long idDe(Respuesta respuesta) {
        assertThat(respuesta.estado()).isIn(200, 201);
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(respuesta.cuerpo());
        assertThat(buscador.find()).isTrue();
        return Long.parseLong(buscador.group(1));
    }
}
