package com.alejandriamakeup.pos.metricas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

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
import com.alejandriamakeup.pos.inventario.ServicioInventario;
import com.alejandriamakeup.pos.inventario.dto.PeticionesInventario;
import com.alejandriamakeup.pos.metricas.dto.MetricasDto;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * La razón de ser de {@code precio_unitario_congelado} y
 * {@code costo_unitario_congelado}, demostrada.
 *
 * <p>Se calcula una métrica, se cambian precio y costo en el catálogo, se vuelve a
 * calcular, y tiene que dar exactamente lo mismo. Si algún día alguien "simplifica"
 * el módulo con un {@code join} contra {@code variante} para leer el precio —que es
 * la forma natural de escribirlo si no se conoce esta regla—, subir un precio
 * reescribiría el margen de todo lo vendido el año pasado, hacia arriba, en silencio
 * y sin dejar rastro. No hay forma de notarlo mirando la pantalla: los números
 * seguirían siendo verosímiles.
 *
 * <p>El precio se cambia por {@code ServicioVariante.actualizar}, que es lo que hay
 * detrás del endpoint de edición del catálogo. No por HTTP: hoy
 * {@code PUT /api/v1/catalogo/variantes/{id}} responde 500 por un fallo del
 * catálogo anterior a esta fase —el controlador navega {@code producto.marca} para
 * armar el DTO fuera de la transacción y con {@code open-in-view: false} eso lanza—,
 * y {@code CatalogoCrudHttpTest} ya está en rojo por lo mismo. Este test no puede
 * depender de un endpoint roto por otra razón.
 *
 * <p>El costo promedio no tiene endpoint —lo escribe solo el ledger, ver
 * {@code CostoPromedioNoEditableTest}— así que se fuerza por el repositorio: lo que
 * se está probando aquí es que las métricas no lo lean, no cómo cambió.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CamposCongeladosTest {

    private static final String URL = BaseDatosAislada.urlNueva("metricas-congeladas");

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
    private VarianteRepository varianteRepository;

    @Test
    void cambiarElPrecioYElCostoDeHoyNoMueveElMargenDeLoYaVendido() {
        long idDuena = crearDuena();

        Marca marca = servicioMarca.crear("NYX");
        Categoria categoria = servicioCategoria.crear("Labios");
        Producto producto = servicioProducto.crear(new PeticionesCatalogo.Producto(
                "Labial", marca.getId(), categoria.getId(), null));
        Variante variante = servicioVariante.crear(new PeticionesCatalogo.Variante(
                producto.getId(), "Rojo", "5 ml", null, 20_000L, 0, null, null));

        servicioInventario.cargaInicial(new PeticionesInventario.CargaInicial(List.of(
                new PeticionesInventario.CargaInicial.Linea(variante.getId(), 10, 12_000L))),
                idDuena);

        ClienteHttpDePrueba duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        duena.post("/api/v1/caja/sesiones", "{}");
        duena.post("/api/v1/ventas", "{\"uuid\":\"congelado\",\"metodoPago\":\"EFECTIVO\","
                + "\"efectivoRecibido\":100000,\"lineas\":[{\"varianteId\":" + variante.getId()
                + ",\"cantidad\":2}]}");

        MetricasDto.Resumen antes = panel().actual();

        // 2 x 20.000 de ingreso, 2 x 12.000 de costo: 40% de margen.
        assertThat(antes.ingreso()).isEqualTo(40_000);
        assertThat(antes.costo()).isEqualTo(24_000);
        assertThat(antes.margenPorcentaje()).isEqualTo(40);

        // El precio se duplica por el catálogo y el costo baja a la mitad en el ledger.
        // Leyendo el catálogo, el margen de esa misma venta pasaría de 40% a 85%.
        servicioVariante.actualizar(variante.getId(), new PeticionesCatalogo.Variante(
                producto.getId(), "Rojo", "5 ml", null, 40_000L, 0, null, null));

        Variante recargada = varianteRepository.findById(variante.getId()).orElseThrow();
        recargada.setCostoPromedio(6_000);
        varianteRepository.save(recargada);

        // Que el catálogo de verdad cambió: si no, el test pasaría por no haber pasado
        // nada, que es la forma más común de que una prueba esté en verde sin afirmar
        // nada.
        Variante enElCatalogo = varianteRepository.findById(variante.getId()).orElseThrow();
        assertThat(enElCatalogo.getPrecioVenta()).isEqualTo(40_000);
        assertThat(enElCatalogo.getCostoPromedio()).isEqualTo(6_000);

        MetricasDto.Resumen despues = panel().actual();

        System.out.println("VERIFICACION antes  => " + antes);
        System.out.println("VERIFICACION después => " + despues);
        System.out.println("    precio 20.000 -> 40.000, costo 12.000 -> 6.000; "
                + "leyendo el catálogo el margen diría 85%");

        assertThat(despues)
                .withFailMessage("El margen se movió de %s a %s al cambiar precio y costo en el "
                        + "catálogo. Alguna métrica está leyendo variante en vez de los campos "
                        + "congelados de venta_item, y eso reescribe el pasado.", antes, despues)
                .isEqualTo(antes);

        // El ranking también: es donde vive el margen por producto.
        assertThat(panel().masVendidosPorUnidades().get(0).margen()).isEqualTo(16_000);
    }

    private MetricasDto.Panel panel() {
        return servicioMetricas.panel(Periodo.DIA, LocalDate.now());
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
