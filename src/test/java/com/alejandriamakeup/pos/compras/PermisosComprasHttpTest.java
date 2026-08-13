package com.alejandriamakeup.pos.compras;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

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
 * El módulo de compras entero le responde 403 a la EMPLEADA.
 *
 * <p>La navegación no le muestra la sección, pero eso no protege nada: esconder un
 * botón solo evita ofrecer algo que va a fallar. Lo que protege es esto, y por eso
 * se comprueba ruta por ruta y no "el módulo" en abstracto — una ruta nueva que
 * alguien agregue mañana sin regla la niega el interceptor por defecto, pero una
 * agregada con la regla equivocada solo la atrapa una lista explícita.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PermisosComprasHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("permisos-compras");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    /** Las catorce rutas del módulo, con un cuerpo válido donde hace falta. */
    private static final List<String[]> RUTAS = List.of(
            new String[] {"GET", "/api/v1/proveedores", null},
            new String[] {"POST", "/api/v1/proveedores", "{\"nombre\":\"X\"}"},
            new String[] {"PUT", "/api/v1/proveedores/1", "{\"nombre\":\"X\"}"},
            new String[] {"POST", "/api/v1/proveedores/1/desactivacion", null},
            new String[] {"POST", "/api/v1/proveedores/1/reactivacion", null},
            new String[] {"GET", "/api/v1/compras", null},
            new String[] {"POST", "/api/v1/compras",
                    "{\"proveedorId\":1,\"lineas\":[{\"varianteId\":1,\"cantidad\":1,\"costoUnitario\":1}]}"},
            new String[] {"GET", "/api/v1/compras/1", null},
            new String[] {"PUT", "/api/v1/compras/1",
                    "{\"proveedorId\":1,\"lineas\":[{\"varianteId\":1,\"cantidad\":1,\"costoUnitario\":1}]}"},
            new String[] {"POST", "/api/v1/compras/1/descarte", "{\"motivo\":\"x\"}"},
            new String[] {"GET", "/api/v1/compras/1/previa-recepcion", null},
            new String[] {"POST", "/api/v1/compras/1/recepcion", null},
            new String[] {"GET", "/api/v1/compras/1/previa-anulacion", null},
            new String[] {"POST", "/api/v1/compras/1/anulacion", "{\"motivo\":\"x\"}"});

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;

    private static boolean sembrado;
    private ClienteHttpDePrueba empleada;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void entrar() {
        if (!sembrado) {
            crear("Alejandra", Rol.DUENA, "1111");
            crear("Camila", Rol.EMPLEADA, "2222");
            sembrado = true;
        }
        empleada = new ClienteHttpDePrueba(puerto);
        empleada.post("/api/v1/auth/login", "{\"nombre\":\"Camila\",\"pin\":\"2222\"}");
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void laEmpleadaRecibe403EnTodoElModuloDeCompras() {
        List<String> coladas = new ArrayList<>();

        for (String[] ruta : RUTAS) {
            Respuesta respuesta = llamar(empleada, ruta);
            System.out.println("    " + respuesta.estado() + "  " + ruta[0] + " " + ruta[1]);
            if (respuesta.estado() != 403) {
                coladas.add(ruta[0] + " " + ruta[1] + " => " + respuesta.estado()
                        + " " + respuesta.cuerpo());
            }
        }

        System.out.println("VERIFICACION EMPLEADA contra " + RUTAS.size()
                + " rutas de compras => coladas: " + coladas.size());
        assertThat(coladas)
                .withFailMessage("Estas rutas de compras no le dieron 403 a la EMPLEADA:%n%s",
                        String.join("\n", coladas))
                .isEmpty();
    }

    /**
     * La contraparte. Sin esto, un módulo que le respondiera 403 a todo el mundo
     * pasaría el test de arriba y dejaría a la dueña sin poder comprar.
     */
    @Test
    void laDuenaNoRecibe403EnNinguna() {
        List<String> negadas = new ArrayList<>();

        for (String[] ruta : RUTAS) {
            Respuesta respuesta = llamar(duena, ruta);
            if (respuesta.estado() == 403) {
                negadas.add(ruta[0] + " " + ruta[1] + " => " + respuesta.cuerpo());
            }
        }

        System.out.println("VERIFICACION DUENA contra las mismas rutas => negadas: "
                + negadas.size());
        assertThat(negadas)
                .withFailMessage("A la DUENA le negaron estas rutas:%n%s", String.join("\n", negadas))
                .isEmpty();
    }

    private Respuesta llamar(ClienteHttpDePrueba cliente, String[] ruta) {
        return switch (ruta[0]) {
            case "GET" -> cliente.get(ruta[1]);
            case "PUT" -> cliente.put(ruta[1], ruta[2]);
            case "POST" -> ruta[2] == null ? cliente.post(ruta[1]) : cliente.post(ruta[1], ruta[2]);
            default -> throw new IllegalArgumentException("Método no contemplado: " + ruta[0]);
        };
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
