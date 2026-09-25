package com.alejandriamakeup.pos.caja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

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
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * La caja que se quedó abierta de ayer.
 *
 * <p>Pasa de verdad: la tienda cierra, alguien apaga el equipo sin cerrar la sesión, y
 * al día siguiente se quiere abrir caja como si nada. No se permite: primero se cierra
 * la de ayer. Y el mensaje tiene que decir <em>eso</em>, no un genérico "ya hay una
 * sesión abierta", porque lo que hay que hacer es distinto.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class SesionDeDiaAnteriorTest {

    private static final String URL = BaseDatosAislada.urlNueva("sesion-de-ayer");

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SesionCajaRepository sesionRepository;

    @Autowired
    private MovimientoCajaRepository movimientoRepository;

    @Autowired
    private ServicioSesionCaja servicioSesion;

    private ClienteHttpDePrueba duena;
    private static Long idSesionDeAyer;

    @BeforeEach
    void prepararSesionOlvidada() {
        Usuario duenaEntidad = usuarioRepository.findByNombre("Alejandra").orElseGet(() -> {
            Usuario nueva = new Usuario();
            nueva.setNombre("Alejandra");
            nueva.setRol(Rol.DUENA);
            nueva.setPinHash(new BCryptPasswordEncoder().encode("1111"));
            nueva.setActivo(true);
            nueva.setFechaCreacion(Fechas.ahora());
            return usuarioRepository.save(nueva);
        });

        if (sesionRepository.buscarAbierta().isEmpty() && idSesionDeAyer == null) {
            SesionCaja deAyer = new SesionCaja();
            // Fabricada a mano, así que su consecutivo no puede chocar con el que
            // generará el contador cuando el test abra la sesión de hoy: ese empieza
            // en S-000001.
            deAyer.setConsecutivo("S-000999");
            deAyer.setUsuarioApertura(duenaEntidad);
            deAyer.setFechaApertura(Fechas.ahora().minusDays(1));
            deAyer.setEstado(EstadoSesionCaja.ABIERTA);
            SesionCaja guardada = sesionRepository.save(deAyer);
            idSesionDeAyer = guardada.getId();

            // Lo que hay en su cajón entró como movimiento: ya no existe una base que
            // lo aporte sin dejar rastro. Es el esperado de esta sesión: 180.000.
            movimientoRepository.save(MovimientoCaja.builder()
                    .sesion(guardada)
                    .tipo(TipoMovimientoCaja.INGRESO)
                    .monto(180_000)
                    .concepto("efectivo dejado de sesión anterior")
                    .usuario(duenaEntidad)
                    .fecha(deAyer.getFechaApertura())
                    .build());
        }

        duena = new ClienteHttpDePrueba(puerto);
        duena.post("/api/v1/auth/login", "{\"nombre\":\"Alejandra\",\"pin\":\"1111\"}");
    }

    @Test
    @Order(1)
    void noSePuedeAbrirYElMensajeDiceQueEsDeUnDiaAnterior() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones", "{}");

        System.out.println("VERIFICACION abrir con la caja de ayer abierta => "
                + respuesta.estado() + " " + respuesta.cuerpo());
        assertThat(respuesta.estado()).isEqualTo(409);
        assertThat(respuesta.cuerpo()).contains("SESION_ABIERTA_DE_DIA_ANTERIOR");
        assertThat(respuesta.cuerpo()).contains(LocalDate.now().minusDays(1).toString());
        // Y no filtra la base de esa sesión, que sigue abierta.
        assertThat(respuesta.cuerpo()).doesNotContain("180000");
    }

    /**
     * El agujero que el bloqueo de apertura no tapaba: <strong>nadie necesita abrir
     * caja para operar</strong>.
     *
     * <p>Con la sesión de ayer todavía abierta, un gasto de hoy la encuentra y entra
     * en ella. El arqueo de ayer termina con la plata de hoy adentro, y el descuadre
     * no se ve: la sesión cuadra consigo misma, solo que abarca dos días. En la Fase 3
     * el mismo agujero afectaría a las ventas, que es peor.
     */
    @Test
    @Order(2)
    void tampocoSePuedeOperarSobreLaSesionDeAyer() {
        Respuesta gasto = duena.post("/api/v1/caja/movimientos",
                "{\"tipo\":\"GASTO\",\"monto\":12000,\"concepto\":\"gasto de hoy\"}");

        System.out.println("VERIFICACION gasto de hoy con la caja de ayer abierta => "
                + gasto.estado() + " " + gasto.cuerpo());
        assertThat(gasto.estado()).isEqualTo(409);
        assertThat(gasto.cuerpo()).contains("SESION_ABIERTA_DE_DIA_ANTERIOR");
        assertThat(gasto.cuerpo()).contains("entra en el arqueo de ese día");

        // Y no quedó registrado en la sesión de ayer.
        Respuesta movimientos = duena.get("/api/v1/caja/sesiones/" + idSesionDeAyer + "/movimientos");
        assertThat(movimientos.cuerpo()).doesNotContain("gasto de hoy");
    }

    /** La sesión abierta se autodenuncia, para que el front pueda avisar. */
    @Test
    @Order(3)
    void laSesionAbiertaDiceQueEsDeUnDiaAnterior() {
        Respuesta actual = duena.get("/api/v1/caja/sesiones/actual");

        System.out.println("VERIFICACION sesión actual se autodenuncia => " + actual.cuerpo());
        assertThat(actual.cuerpo()).contains("\"esDeUnDiaAnterior\":true");
        // Sigue sin revelar la base, aunque esté en falta.
        assertThat(actual.cuerpo()).doesNotContain("180000");
    }

    @Test
    @Order(4)
    void elServicioTambienLoDiceAQuienLoPregunteDirectamente() {
        System.out.println("VERIFICACION hayUnaSesionOlvidadaDeUnDiaAnterior => "
                + servicioSesion.hayUnaSesionOlvidadaDeUnDiaAnterior());
        assertThat(servicioSesion.hayUnaSesionOlvidadaDeUnDiaAnterior()).isTrue();

        assertThatThrownBy(() -> servicioSesion.sesionOperableHoy())
                .isInstanceOf(ErrorDeAplicacion.class)
                .hasMessageContaining("Hay que cerrarla antes de seguir operando");
    }

    /**
     * "Con su fecha real" se implementa así: la fecha de cierre es el momento en que
     * de verdad se cerró, nunca una que mande el cliente. Una caja antedatada es un
     * agujero de auditoría, y el endpoint no acepta fechas justamente para que no
     * exista la tentación.
     */
    @Test
    @Order(5)
    void alCerrarlaLaFechaDeCierreEsAhoraYNoLaDeAyer() {
        Respuesta cierre = duena.post("/api/v1/caja/sesiones/" + idSesionDeAyer + "/cierre",
                "{\"conteo\":[{\"denominacion\":50000,\"cantidad\":3}],"
                        + "\"montoRetirado\":30000,"
                        + "\"observaciones\":\"quedó abierta de ayer\"}");

        System.out.println("VERIFICACION cierre de la sesión de ayer => " + cierre.cuerpo());
        assertThat(cierre.estado()).isEqualTo(200);
        assertThat(cierre.cuerpo()).contains("\"fechaApertura\":\"" + LocalDate.now().minusDays(1));
        assertThat(cierre.cuerpo()).contains("\"fechaCierre\":\"" + LocalDate.now());
        // 150.000 contados contra 180.000 esperados: faltan 30.000.
        assertThat(cierre.cuerpo())
                .contains("\"efectivoEsperado\":180000")
                .contains("\"efectivoContado\":150000")
                .contains("\"diferencia\":-30000");
    }

    @Test
    @Order(6)
    void cerradaLaDeAyerYaSePuedeAbrirLaDeHoy() {
        Respuesta respuesta = duena.post("/api/v1/caja/sesiones", "{}");

        System.out.println("VERIFICACION abrir tras cerrar la de ayer => " + respuesta.estado());
        assertThat(respuesta.estado()).isEqualTo(201);
    }
}
