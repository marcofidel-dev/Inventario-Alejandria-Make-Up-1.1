package com.alejandriamakeup.pos.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * El comportamiento del interceptor en caliente: negar por defecto, y exigir sesión.
 *
 * <p>El test de la ruta sin regla usa un controlador que solo existe en las fuentes
 * de test y que está deliberadamente <strong>sin declarar</strong> en
 * {@link ReglasDeAcceso}. Es la simulación de lo que va a pasar en la Fase 3 cuando
 * alguien escriba un endpoint y olvide su regla: tiene que salir negado, no abierto.
 */
@SpringBootTest(
        classes = {PosApplication.class, AutorizacionHttpTest.ControladorSinRegla.class},
        webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AutorizacionHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("autorizacion");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private ClienteHttpDePrueba cliente;

    @BeforeEach
    void prepararUsuariaYCliente() {
        cliente = new ClienteHttpDePrueba(puerto);
        if (usuarioRepository.count() == 0) {
            Usuario duena = new Usuario();
            duena.setNombre("Alejandra");
            duena.setRol(Rol.DUENA);
            duena.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            duena.setActivo(true);
            duena.setFechaCreacion(LocalDateTime.now());
            usuarioRepository.save(duena);
        }
    }

    @Test
    void unaRutaDeLaApiSinReglaSeNiega() {
        var respuesta = cliente.get("/api/v1/prueba/sin-regla");

        System.out.println("VERIFICACION GET ruta sin regla => " + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("RUTA_SIN_REGLA");
        assertThat(respuesta.cuerpo()).doesNotContain("secreto-que-no-debe-salir");
    }

    @Test
    void unaRutaSinReglaSigueNegadaAunConSesionIniciada() {
        cliente.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");

        var respuesta = cliente.get("/api/v1/prueba/sin-regla");

        System.out.println("VERIFICACION GET ruta sin regla, autenticada => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(403);
    }

    @Test
    void unaRutaQueExigeSesionResponde401SinSesion() {
        var respuesta = cliente.get("/api/v1/auth/sesion");

        System.out.println("VERIFICACION GET /auth/sesion sin sesión => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(401);
        assertThat(respuesta.cuerpo()).contains("NO_AUTENTICADO");
    }

    @Test
    void laMismaRutaFuncionaConSesion() {
        var login = cliente.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
        assertThat(login.estado()).isEqualTo(200);

        var respuesta = cliente.get("/api/v1/auth/sesion");

        System.out.println("VERIFICACION GET /auth/sesion con sesión => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("Alejandra").contains("DUENA");
        assertThat(respuesta.cuerpo()).doesNotContain("pinHash");
    }

    /**
     * Los perfiles son públicos porque la pantalla de login los necesita antes de que
     * exista sesión, y llevan <strong>solo el nombre</strong>: el rol le diría a
     * cualquiera en la wifi de la tienda cuál cuenta administra el sistema.
     */
    @Test
    void losPerfilesSonPublicosYSoloTraenElNombre() {
        var respuesta = anonimo().get("/api/v1/auth/perfiles");

        System.out.println("VERIFICACION GET /auth/perfiles sin sesión => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("Alejandra");
        assertThat(respuesta.cuerpo())
                .withFailMessage("Los perfiles exponen el rol: %s", respuesta.cuerpo())
                .doesNotContain("DUENA").doesNotContain("rol");
        assertThat(respuesta.cuerpo()).doesNotContain("pinHash").doesNotContain("id");
    }

    private ClienteHttpDePrueba anonimo() {
        return new ClienteHttpDePrueba(puerto);
    }

    @Test
    void laRutaPublicaDeSaludNoExigeSesion() {
        var respuesta = cliente.get("/api/v1/health");

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("UP");
    }

    /**
     * Un endpoint bajo {@code /api/**} que nadie declaró en {@link ReglasDeAcceso}.
     * Devuelve algo reconocible para que el test pueda comprobar que el cuerpo
     * <strong>no</strong> llegó al cliente.
     */
    @RestController
    @RequestMapping("/api/v1/prueba")
    static class ControladorSinRegla {

        @GetMapping("/sin-regla")
        public String sinRegla() {
            return "secreto-que-no-debe-salir";
        }
    }
}
