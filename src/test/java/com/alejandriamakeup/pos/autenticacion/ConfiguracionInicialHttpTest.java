package com.alejandriamakeup.pos.autenticacion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;

/**
 * El primer arranque: sin ningún usuario, la aplicación no deja entrar a nada hasta
 * que se cree la administradora.
 *
 * <p>Tiene base de datos propia porque la condición que prueba es global — "no hay
 * ningún usuario" — y contra la base compartida cualquier otro test que cree uno la
 * destruiría. Y va con orden explícito porque es un escenario secuencial: el estado
 * cambia de "sin configurar" a "configurada" y no se puede volver atrás.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class ConfiguracionInicialHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("configuracion-inicial");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    private ClienteHttpDePrueba cliente;

    @BeforeEach
    void prepararCliente() {
        cliente = new ClienteHttpDePrueba(puerto);
    }

    @Test
    @Order(1)
    void elEstadoAvisaQueFaltaConfigurar() {
        var respuesta = cliente.get("/api/v1/auth/estado");

        System.out.println("VERIFICACION GET /auth/estado sin usuarios => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"requiereConfiguracionInicial\":true");
    }

    /**
     * Los perfiles responden lista vacía y no 409. La pantalla de login los pide siempre,
     * y una lista vacía es una respuesta que el front puede entender; un 409 la obligaría
     * a tratar el arranque como un caso especial más.
     */
    @Test
    @Order(2)
    void sinUsuariosLosPerfilesSonUnaListaVacia() {
        var respuesta = cliente.get("/api/v1/auth/perfiles");

        System.out.println("VERIFICACION GET /auth/perfiles sin usuarios => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).isEqualTo("[]");
    }

    @Test
    @Order(3)
    void sinUsuariosNingunEndpointDeDatosResponde() {
        var caja = cliente.get("/api/v1/caja/sesiones/actual");
        var login = cliente.post("/api/v1/auth/login", "{\"nombre\":\"quienquiera\",\"pin\":\"1234\"}");

        System.out.println("VERIFICACION GET /caja/sesiones/actual sin usuarios => "
                + caja.estado() + " " + caja.cuerpo());
        System.out.println("VERIFICACION POST /auth/login sin usuarios => " + login.estado());

        assertThat(caja.estado()).isEqualTo(409);
        assertThat(caja.cuerpo()).contains("CONFIGURACION_INICIAL_REQUERIDA");
        // Ni siquiera iniciar sesión: no hay con quién.
        assertThat(login.estado()).isEqualTo(409);
    }

    @Test
    @Order(4)
    void crearLaAdministradoraAbreLaAplicacion() {
        var creacion = cliente.post("/api/v1/auth/configuracion-inicial",
                "{\"nombre\":\"Alejandra\",\"pin\":\"9137\"}");

        System.out.println("VERIFICACION POST /auth/configuracion-inicial => "
                + creacion.estado() + " " + creacion.cuerpo());
        assertThat(creacion.estado()).isEqualTo(201);
        assertThat(creacion.cuerpo()).contains("DUENA").contains("Alejandra");
        assertThat(creacion.cuerpo()).doesNotContain("9137").doesNotContain("pinHash");

        var estado = cliente.get("/api/v1/auth/estado");
        assertThat(estado.cuerpo()).contains("\"requiereConfiguracionInicial\":false");

        var login = cliente.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"9137\"}");
        System.out.println("VERIFICACION login con el PIN recién creado => " + login.estado());
        assertThat(login.estado()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void elEndpointDeConfiguracionInicialSeCierraSolo() {
        var segunda = cliente.post("/api/v1/auth/configuracion-inicial",
                "{\"nombre\":\"Intrusa\",\"pin\":\"0000\"}");

        System.out.println("VERIFICACION segundo POST /auth/configuracion-inicial => "
                + segunda.estado() + " " + segunda.cuerpo());
        assertThat(segunda.estado()).isEqualTo(409);
        assertThat(segunda.cuerpo()).contains("CONFIGURACION_INICIAL_YA_HECHA");

        // Y no dejó nada creado.
        var login = cliente.post("/api/v1/auth/login", "{\"nombre\":\"Intrusa\",\"pin\":\"0000\"}");
        assertThat(login.estado()).isEqualTo(401);
    }

    @Test
    @Order(6)
    void elPinDebeSerDeCuatroAOchoDigitos() {
        var corto = cliente.post("/api/v1/auth/configuracion-inicial",
                "{\"nombre\":\"Otra\",\"pin\":\"12\"}");

        System.out.println("VERIFICACION PIN de 2 dígitos => " + corto.estado() + " " + corto.cuerpo());
        assertThat(corto.estado()).isEqualTo(400);
    }
}
