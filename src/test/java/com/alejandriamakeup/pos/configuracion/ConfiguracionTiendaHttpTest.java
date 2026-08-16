package com.alejandriamakeup.pos.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Los datos de la tienda: quién los ve, quién los cambia y con qué nacen.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class ConfiguracionTiendaHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("configuracion-tienda");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;

    private static boolean sembrado;
    private ClienteHttpDePrueba duena;
    private ClienteHttpDePrueba empleada;

    @BeforeEach
    void sembrarYEntrar() {
        if (!sembrado) {
            crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");
            sembrado = true;
        }
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
    }

    /**
     * <strong>La semilla nace en blanco, no con datos de ejemplo.</strong> Un nombre de
     * relleno se imprimiría en el recibo igual que uno real, y nadie lo notaría hasta
     * que una clienta preguntara por una dirección que no existe.
     */
    @Test
    @Order(1)
    void laConfiguracionNaceEnBlancoYNoInventada() {
        Respuesta respuesta = duena.get("/api/v1/configuracion/tienda");

        System.out.println("VERIFICACION semilla => " + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).isEqualTo(
                "{\"nombre\":\"\",\"nit\":\"\",\"direccion\":\"\",\"telefono\":\"\","
                        + "\"pieRecibo\":\"\"}");
    }

    @Test
    @Order(2)
    void seGuardaYSeVuelveALeer() {
        Respuesta guardado = duena.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"Alejandria Make Up\",\"nit\":\"1.234.567.890-1\","
                        + "\"direccion\":\"Cra 10 # 15-30, Puerto Gaitán\","
                        + "\"telefono\":\"300 123 4567\",\"pieRecibo\":\"Gracias por su compra\"}");
        Respuesta releido = duena.get("/api/v1/configuracion/tienda");

        System.out.println("VERIFICACION guardado => " + guardado.estado() + " "
                + guardado.cuerpo());

        assertThat(guardado.estado()).isEqualTo(200);
        assertThat(releido.cuerpo())
                .contains("\"nombre\":\"Alejandria Make Up\"")
                .contains("\"nit\":\"1.234.567.890-1\"")
                .contains("\"direccion\":\"Cra 10 # 15-30, Puerto Gaitán\"")
                .contains("\"telefono\":\"300 123 4567\"")
                .contains("\"pieRecibo\":\"Gracias por su compra\"");
    }

    /**
     * Ningún campo es obligatorio.
     *
     * <p>Con el nombre obligatorio, una tienda recién instalada no podría guardar el
     * teléfono hasta tener decidida la razón social — y la validación empujaría a
     * inventar algo con tal de seguir, que es exactamente lo que la semilla en blanco
     * evita.
     */
    @Test
    @Order(3)
    void sePuedeGuardarSinNombre() {
        Respuesta respuesta = duena.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"\",\"nit\":\"\",\"direccion\":\"\","
                        + "\"telefono\":\"300 123 4567\",\"pieRecibo\":\"\"}");

        System.out.println("VERIFICACION guardar sin nombre => " + respuesta.estado()
                + " " + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"telefono\":\"300 123 4567\"");
    }

    /**
     * <strong>Solo la DUENA</strong>, en las dos direcciones. Leer también: el NIT y la
     * razón social son la identidad fiscal del negocio.
     */
    @Test
    @Order(4)
    void laEmpleadaNiLoLeeNiLoCambia() {
        Respuesta lectura = empleada.get("/api/v1/configuracion/tienda");
        Respuesta escritura = empleada.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"La tienda de Camila\"}");

        System.out.println("VERIFICACION EMPLEADA => leer " + lectura.estado()
                + ", escribir " + escritura.estado());

        assertThat(lectura.estado()).isEqualTo(403);
        assertThat(escritura.estado()).isEqualTo(403);
        assertThat(lectura.cuerpo()).contains("SIN_PERMISO");

        // Y no cambió nada: el 403 se impone antes de llegar al servicio.
        assertThat(duena.get("/api/v1/configuracion/tienda").cuerpo())
                .doesNotContain("La tienda de Camila");
    }

    /** El error tiene la forma de siempre: {codigo, error}. */
    @Test
    @Order(5)
    void unNombreDemasiadoLargoSeRechazaConLaFormaDeErrorDeSiempre() {
        Respuesta respuesta = duena.put("/api/v1/configuracion/tienda",
                "{\"nombre\":\"" + "M".repeat(61) + "\"}");

        System.out.println("VERIFICACION nombre de 61 caracteres => " + respuesta.estado()
                + " " + respuesta.cuerpo());

        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":").contains("\"error\":");
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
