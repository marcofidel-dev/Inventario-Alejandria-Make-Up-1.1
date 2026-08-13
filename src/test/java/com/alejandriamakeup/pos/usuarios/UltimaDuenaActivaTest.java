package com.alejandriamakeup.pos.usuarios;

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

/**
 * No se puede desactivar a la última DUENA activa.
 *
 * <p>El daño que evita es de los peores que puede tener esta aplicación: sin ninguna
 * administradora activa nadie puede editar el catálogo, ajustar inventario, ver costos ni
 * volver a activar a nadie. Y como {@code /auth/configuracion-inicial} se cierra en
 * cuanto existe un usuario — contando activos <em>e inactivos</em>, justamente para que
 * desactivar a todo el mundo no sea una vía de escalada de privilegios — tampoco se puede
 * crear una administradora nueva. La tienda quedaría con la llave por fuera, sin arreglo
 * desde la propia aplicación.
 *
 * <p>Va ordenado porque es un escenario: primero con una sola DUENA, después con dos.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class UltimaDuenaActivaTest {

    private static final String URL = BaseDatosAislada.urlNueva("ultima-duena");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ServicioUsuario servicioUsuario;

    private static Long idAlejandra;
    private static Long idCamila;
    private static Long idSegundaDuena;

    private ClienteHttpDePrueba duena;

    /** Los PIN de las dos administradoras del escenario. */
    private static final java.util.Map<String, String> PINES =
            java.util.Map.of("Alejandra", "1111", "Marcela", "3333");

    @BeforeEach
    void prepararUsuariasYEntrar() {
        if (idAlejandra == null) {
            idAlejandra = crear("Alejandra", Rol.DUENA, "1111", true);
            idCamila = crear("Camila", Rol.EMPLEADA, "2222", true);
            idSegundaDuena = crear("Marcela", Rol.DUENA, "3333", false);
        }

        // Entra la DUENA que esté activa en este punto del escenario, no una fija: el
        // test 3 desactiva a Alejandra, y una usuaria inactiva no puede iniciar sesión
        // -- lo cual es correcto y hay que acomodarse a ello, no sortearlo.
        Usuario activa = usuarioRepository.findAll().stream()
                .filter(u -> u.getRol() == Rol.DUENA && u.isActivo())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No quedó ninguna DUENA activa: la regla que este test protege se rompió"));

        duena = new ClienteHttpDePrueba(puerto);
        assertThat(duena.post("/api/v1/auth/login", "{\"nombre\":\"" + activa.getNombre()
                + "\",\"pin\":\"" + PINES.get(activa.getNombre()) + "\"}").estado())
                .withFailMessage("No pudo entrar la DUENA activa %s", activa.getNombre())
                .isEqualTo(200);
    }

    @Test
    @Order(1)
    void laUnicaDuenaActivaNoPuedeDesactivarseASiMisma() {
        Respuesta respuesta = duena.post("/api/v1/usuarios/" + idAlejandra + "/desactivacion");

        System.out.println("VERIFICACION la única DUENA se desactiva a sí misma => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("ULTIMA_DUENA_ACTIVA");
        assertThat(respuesta.cuerpo()).contains("única");

        // Y sigue activa: el intento no dejó nada a medias.
        assertThat(usuarioRepository.findById(idAlejandra).orElseThrow().isActivo()).isTrue();
    }

    /** Desactivar a la EMPLEADA sí se puede: no es quien administra. */
    @Test
    @Order(2)
    void aLaEmpleadaSiSeLaPuedeDesactivar() {
        Respuesta respuesta = duena.post("/api/v1/usuarios/" + idCamila + "/desactivacion");

        System.out.println("VERIFICACION desactivar a la EMPLEADA => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"activo\":false");

        duena.post("/api/v1/usuarios/" + idCamila + "/reactivacion");
    }

    /** Con una segunda DUENA activa, la primera ya puede retirarse. */
    @Test
    @Order(3)
    void conDosDuenasActivasSiSePuedeDesactivarAUna() {
        assertThat(duena.post("/api/v1/usuarios/" + idSegundaDuena + "/reactivacion").estado())
                .isEqualTo(200);

        Respuesta respuesta = duena.post("/api/v1/usuarios/" + idAlejandra + "/desactivacion");

        System.out.println("VERIFICACION con dos DUENA activas, desactivar una => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(usuarioRepository.countByRolAndActivoTrue(Rol.DUENA)).isEqualTo(1);
    }

    /** Y entonces la que queda vuelve a ser la última: tampoco puede irse. */
    @Test
    @Order(4)
    void laQueQuedaVuelveASerLaUltima() {
        Respuesta respuesta = duena.post("/api/v1/usuarios/" + idSegundaDuena + "/desactivacion");

        System.out.println("VERIFICACION la que queda intenta desactivarse => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("ULTIMA_DUENA_ACTIVA");
        assertThat(usuarioRepository.countByRolAndActivoTrue(Rol.DUENA)).isEqualTo(1);
    }

    /**
     * La otra mitad de la protección: el pestillo de la configuración inicial cuenta
     * usuarios activos <strong>e inactivos</strong>. Si contara solo activos, dejar a
     * todo el mundo inactivo reabriría el endpoint de configuración inicial y cualquiera
     * podría nombrarse administradora.
     */
    @Test
    @Order(5)
    void conUsuariosInactivosLaConfiguracionInicialSigueCerrada() {
        long inactivos = usuarioRepository.findAll().stream().filter(u -> !u.isActivo()).count();
        assertThat(inactivos).isPositive();

        ClienteHttpDePrueba anonimo = new ClienteHttpDePrueba(puerto);
        Respuesta estado = anonimo.get("/api/v1/auth/estado");
        Respuesta intento = anonimo.post("/api/v1/auth/configuracion-inicial",
                "{\"nombre\":\"Intrusa\",\"pin\":\"0000\"}");

        System.out.println("VERIFICACION con " + inactivos + " usuario(s) inactivo(s) => estado: "
                + estado.cuerpo() + " | configuracion-inicial: " + intento.estado());

        assertThat(estado.cuerpo()).contains("\"requiereConfiguracionInicial\":false");
        assertThat(intento.estado()).isEqualTo(409);
        assertThat(intento.cuerpo()).contains("CONFIGURACION_INICIAL_YA_HECHA");
    }

    /** Desactivar a una DUENA que ya está inactiva no es un error: no cambia nada. */
    @Test
    @Order(6)
    void desactivarAUnaDuenaYaInactivaNoSeBloquea() {
        assertThat(usuarioRepository.findById(idAlejandra).orElseThrow().isActivo()).isFalse();

        Respuesta respuesta = duena.post("/api/v1/usuarios/" + idAlejandra + "/desactivacion");

        System.out.println("VERIFICACION desactivar a una DUENA ya inactiva => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(200);
    }

    /** Y el servicio dice lo mismo si se lo pregunta directamente. */
    @Test
    @Order(7)
    void elServicioImponeLaReglaSinPasarPorHttp() {
        long activas = usuarioRepository.countByRolAndActivoTrue(Rol.DUENA);
        Usuario ultima = usuarioRepository.findAll().stream()
                .filter(u -> u.getRol() == Rol.DUENA && u.isActivo())
                .findFirst()
                .orElseThrow();

        System.out.println("VERIFICACION DUENA activas: " + activas + ", intentando desactivar a "
                + ultima.getNombre());
        assertThat(activas).isEqualTo(1);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> servicioUsuario.cambiarActivo(ultima.getId(), false))
                .isInstanceOf(com.alejandriamakeup.pos.web.ErrorDeAplicacion.class)
                .hasMessageContaining("única administradora activa");
    }

    private Long crear(String nombre, Rol rol, String pin, boolean activo) {
        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setRol(rol);
        usuario.setPinHash(new BCryptPasswordEncoder().encode(pin));
        usuario.setActivo(activo);
        usuario.setFechaCreacion(Fechas.ahora());
        return usuarioRepository.save(usuario).getId();
    }
}
