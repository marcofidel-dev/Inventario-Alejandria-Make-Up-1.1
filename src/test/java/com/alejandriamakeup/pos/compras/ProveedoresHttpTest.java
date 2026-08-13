package com.alejandriamakeup.pos.compras;

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
 * Proveedores por HTTP, con el foco en los duplicados.
 *
 * <p>La razón de que esto exista aparte del catálogo: hasta la V5, {@code proveedor}
 * tenía un índice único plano sobre el nombre, que solo atrapa cadenas idénticas.
 * "Distribuciones Lopez" y "Distribuciones López" convivían tan tranquilas, y en seis
 * meses nadie sabría a cuál de las dos se le compró qué.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProveedoresHttpTest {

    private static final String URL = BaseDatosAislada.urlNueva("proveedores-http");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

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
    void crearUnProveedorGuardaSusDatosDeContacto() {
        Respuesta respuesta = duena.post("/api/v1/proveedores",
                "{\"nombre\":\"Cosmeticos del Valle\",\"nit\":\"900123456-7\","
                        + "\"telefono\":\"3001234567\",\"contacto\":\"Marta\",\"notas\":\"Entrega los martes\"}");

        System.out.println("VERIFICACION proveedor creado => " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(201);
        assertThat(respuesta.cuerpo())
                .contains("\"nombre\":\"Cosmeticos del Valle\"")
                .contains("\"nit\":\"900123456-7\"")
                .contains("\"activo\":true");
    }

    /**
     * Tildes y mayúsculas no hacen un proveedor distinto, y el 409 nombra al que ya
     * está: sin ese nombre, quien lo ve no entiende con qué chocó.
     */
    @Test
    void unNombreQueSoloDifiereEnTildesOMayusculasChoca() {
        duena.post("/api/v1/proveedores", "{\"nombre\":\"Distribuciones López\"}");

        Respuesta respuesta = duena.post("/api/v1/proveedores",
                "{\"nombre\":\"DISTRIBUCIONES LOPEZ\"}");

        System.out.println("VERIFICACION duplicado normalizado => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo())
                .contains("\"codigo\":\"NOMBRE_DUPLICADO\"")
                .contains("Distribuciones López");
    }

    /** Renombrarse a sí mismo no es chocar consigo mismo. */
    @Test
    void editarUnProveedorSinCambiarleElNombreNoChocaConsigoMismo() {
        long id = idDe(duena.post("/api/v1/proveedores", "{\"nombre\":\"Belleza y Cia\"}"));

        Respuesta respuesta = duena.put("/api/v1/proveedores/" + id,
                "{\"nombre\":\"Belleza y Cia\",\"telefono\":\"3009999999\"}");

        System.out.println("VERIFICACION edición sin choque => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cuerpo()).contains("\"telefono\":\"3009999999\"");
    }

    /** No se borra: se desactiva, y se puede volver a activar. */
    @Test
    void desactivarYReactivarUnProveedor() {
        long id = idDe(duena.post("/api/v1/proveedores", "{\"nombre\":\"Temporal SAS\"}"));

        Respuesta baja = duena.post("/api/v1/proveedores/" + id + "/desactivacion");
        Respuesta alta = duena.post("/api/v1/proveedores/" + id + "/reactivacion");

        System.out.println("VERIFICACION desactivar/reactivar => " + baja.cuerpo()
                + " | " + alta.cuerpo());
        assertThat(baja.cuerpo()).contains("\"activo\":false");
        assertThat(alta.cuerpo()).contains("\"activo\":true");
    }

    @Test
    void unProveedorSinNombreNoSeCrea() {
        Respuesta respuesta = duena.post("/api/v1/proveedores", "{\"nombre\":\"   \"}");

        System.out.println("VERIFICACION proveedor sin nombre => " + respuesta.estado()
                + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(400);
        assertThat(respuesta.cuerpo()).contains("\"codigo\":\"VALIDACION_FALLIDA\"");
    }

    private long idDe(Respuesta respuesta) {
        var buscador = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(respuesta.cuerpo());
        if (!buscador.find()) {
            throw new IllegalStateException("Sin id en " + respuesta.cuerpo());
        }
        return Long.parseLong(buscador.group(1));
    }
}
