package com.alejandriamakeup.pos.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * El pestillo de la configuración inicial cuenta usuarios <strong>activos e
 * inactivos</strong>.
 *
 * <p>Hace falta una clase aparte, con su propia base y por tanto su propio contexto,
 * porque el pestillo es de un solo sentido: en cuanto se levanta ya no vuelve a
 * consultar. En cualquier otro test la primera petición lo levanta, y a partir de ahí
 * cambiar la consulta no tiene ningún efecto observable — lo comprobé rompiéndolo en
 * otro test y el test siguió pasando.
 *
 * <p>Aquí la base arranca con <strong>un único usuario y está inactivo</strong>, que es el
 * estado exacto que hay que cubrir, y no se hace ninguna petición antes de la afirmación.
 *
 * <p>Qué pasa si el pestillo contara solo los activos, medido rompiéndolo: vería cero,
 * decidiría que falta la configuración inicial, y <strong>la API entera responde 409 a
 * todo</strong> — inservible, aunque haya usuarios. Lo que <em>no</em> pasa es que se
 * pueda crear una administradora nueva: {@code ServicioAutenticacion} vuelve a contar por
 * su cuenta, y también cuenta a los inactivos, así que rechaza el intento igual. Son dos
 * capas independientes contando lo mismo, y hace falta que las dos lo cuenten bien: una
 * para que la app funcione, la otra para que nadie se nombre administradora.
 *
 * <p>El estado se siembra por el repositorio a propósito, salteándose
 * {@code ServicioUsuario} — que precisamente se niega a dejar cero administradoras
 * activas. Cada protección tiene que valerse sola.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PestilloConfiguracionInicialTest {

    private static final String URL = BaseDatosAislada.urlNueva("pestillo");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    void conUnUnicoUsuarioInactivoLaConfiguracionInicialSigueCerrada() {
        Usuario inactiva = new Usuario();
        inactiva.setNombre("Alejandra");
        inactiva.setRol(Rol.DUENA);
        inactiva.setPinHash("$2a$10$hashDePrueba");
        inactiva.setActivo(false);
        inactiva.setFechaCreacion(Fechas.ahora());
        usuarioRepository.save(inactiva);

        assertThat(usuarioRepository.count()).isEqualTo(1);
        assertThat(usuarioRepository.findByActivoTrue()).isEmpty();

        ClienteHttpDePrueba anonimo = new ClienteHttpDePrueba(puerto);

        Respuesta estado = anonimo.get("/api/v1/auth/estado");
        System.out.println("VERIFICACION 1 usuario, 0 activos => /auth/estado: " + estado.cuerpo());
        assertThat(estado.cuerpo())
                .withFailMessage("Con un usuario inactivo la app dice que falta configuración "
                        + "inicial: el pestillo está contando solo los activos")
                .contains("\"requiereConfiguracionInicial\":false");

        // La segunda capa: el servicio cuenta por su cuenta, y también a los inactivos.
        Respuesta intento = anonimo.post("/api/v1/auth/configuracion-inicial",
                "{\"nombre\":\"Intrusa\",\"pin\":\"0000\"}");
        System.out.println("VERIFICACION intento de crearse una DUENA => " + intento.estado()
                + " " + intento.cuerpo());
        assertThat(intento.estado())
                .withFailMessage("Se pudo crear una administradora nueva con usuarios inactivos "
                        + "en la base: cualquiera podría nombrarse administradora")
                .isEqualTo(409);
        assertThat(intento.cuerpo()).contains("CONFIGURACION_INICIAL_YA_HECHA");

        // Y esta es la que caza al pestillo: el resto de la API tiene que pedir sesión,
        // no configuración inicial. Contando solo activos, aquí saldría 409 y la
        // aplicación entera quedaría inservible aunque haya usuarios.
        Respuesta catalogo = anonimo.get("/api/v1/catalogo");
        System.out.println("VERIFICACION GET /catalogo sin sesión => " + catalogo.estado()
                + " " + catalogo.cuerpo());
        assertThat(catalogo.estado())
                .withFailMessage("Con usuarios inactivos la API responde como si faltara la "
                        + "configuración inicial: el pestillo está contando solo los activos y "
                        + "la aplicación queda inservible")
                .isEqualTo(401);
    }
}
