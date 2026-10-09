package com.alejandriamakeup.pos.sistema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba.Respuesta;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Por HTTP real: el cliente de prueba le pega a {@code localhost}, que siempre
 * es loopback, así que esto cubre el camino feliz y el de fallo del respaldo,
 * nunca el 403 por IP no local (eso lo prueba {@link SistemaControllerTest},
 * que sí puede falsear {@code getRemoteAddr()}).
 *
 * <p>{@link ApagadorDeAplicacion} va con {@code @MockitoBean} para que la
 * suite no se autoapague al pasar por aquí.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// BackupService.intentoDeCierreHecho es un pestillo de un solo uso por instancia:
// sin un contexto fresco por método, el segundo test heredaría el pestillo que el
// primero ya gastó y su resultado dependería del orden en que JUnit los corra.
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class SistemaControllerHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("sistema-apagado");

    /** Un archivo, no un directorio: VACUUM INTO no puede escribir ahí, así que el respaldo falla seguro. */
    private static final Path BACKUPS_IMPOSIBLE = archivoQueNoEsDirectorio();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
        registro.add("app.paths.backups", () -> BACKUPS_IMPOSIBLE.toString());
    }

    private static Path archivoQueNoEsDirectorio() {
        try {
            return Files.createTempFile("sistema-backups-no-directorio", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @MockitoBean
    private ApagadorDeAplicacion apagador;

    @LocalServerPort private int puerto;
    @Autowired private UsuarioRepository usuarioRepository;

    private static boolean sembrado;
    private ClienteHttpDePrueba duena;

    @BeforeEach
    void entrar() {
        if (!sembrado) {
            Usuario usuario = new Usuario();
            usuario.setNombre("Alejandra");
            usuario.setRol(Rol.DUENA);
            usuario.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            usuario.setActivo(true);
            usuario.setFechaCreacion(Fechas.ahora());
            usuarioRepository.save(usuario);
            sembrado = true;
        }
        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    void siElRespaldoFallaNoApagaYDevuelveElMotivo() {
        Respuesta respuesta = duena.post("/api/v1/sistema/apagado", "{\"forzar\":false}");

        System.out.println("VERIFICACION respaldo fallido => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"exitoso\":false");
        verify(apagador, never()).programarApagado();
    }

    @Test
    void forzarApagaAunqueElRespaldoYaHayaFallado() {
        duena.post("/api/v1/sistema/apagado", "{\"forzar\":false}");

        Respuesta respuesta = duena.post("/api/v1/sistema/apagado", "{\"forzar\":true}");

        System.out.println("VERIFICACION forzar tras fallo => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"exitoso\":true");
        verify(apagador).programarApagado();
    }
}
