package com.alejandriamakeup.pos.autenticacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

import com.alejandriamakeup.pos.PosApplication;
import com.alejandriamakeup.pos.soporte.BaseDatosAislada;
import com.alejandriamakeup.pos.soporte.ClienteHttpDePrueba;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;

/**
 * Bloqueo tras cinco intentos fallidos.
 *
 * <p>El riesgo no es un atacante remoto — la app no está publicada — sino alguien
 * probando PINs de a uno con el equipo delante. De ahí que el contador esté
 * persistido: si viviera en memoria, cerrar y reabrir la aplicación limpiaría el
 * bloqueo en diez segundos y la protección sería decorativa.
 */
@SpringBootTest(classes = PosApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BloqueoPorIntentosTest {

    private static final String URL = BaseDatosAislada.urlNueva("bloqueo");
    private static final String PIN_BUENO = "4321";

    @DynamicPropertySource
    static void baseAislada(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> URL);
    }

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ServicioAutenticacion servicioAutenticacion;

    @Autowired
    private IntentoLoginRepository intentoRepository;

    private ClienteHttpDePrueba cliente;

    @BeforeEach
    void prepararCliente() {
        cliente = new ClienteHttpDePrueba(puerto);
    }

    /**
     * La política, con sus números escritos a mano.
     *
     * <p>Los demás tests de esta clase usan {@link ServicioAutenticacion#FALLOS_PARA_BLOQUEAR}
     * como cota de sus bucles, lo que los vuelve robustos al mecanismo pero ciegos a
     * la política: subir la constante a 50 los deja a todos en verde, porque se
     * adaptan solos. Lo comprobé rompiéndolo. Este test fija los dos números que pidió
     * la especificación — cinco intentos, cinco minutos — para que cambiarlos sea una
     * decisión y no un descuido.
     */
    @Test
    void laPoliticaEsCincoIntentosYCincoMinutos() {
        System.out.println("VERIFICACION política => " + ServicioAutenticacion.FALLOS_PARA_BLOQUEAR
                + " intentos, ventana de " + ServicioAutenticacion.VENTANA_DE_BLOQUEO.toMinutes()
                + " minutos");
        assertThat(ServicioAutenticacion.FALLOS_PARA_BLOQUEAR).isEqualTo(5);
        assertThat(ServicioAutenticacion.VENTANA_DE_BLOQUEO).isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * El intento fallido tiene que quedar <strong>grabado</strong>, y esto lo prueba
     * directamente en vez de a través del bloqueo.
     *
     * <p>Existe por un bug real que tuvo esta clase: el servicio grababa el intento
     * y acto seguido lanzaba la excepción del 401, que hacía rollback y se llevaba
     * el registro. El contador nunca subía y el bloqueo no se activaba nunca, pero
     * la respuesta al usuario se veía idéntica. Un test que solo mirara el 401
     * habría pasado en verde con la protección completamente muerta.
     */
    @Test
    void unIntentoFallidoQuedaGrabadoAunqueLaRespuestaSeaUnError() {
        String nombre = crearUsuaria("con-fallo-grabado");
        long antes = intentoRepository.fallosDesde(nombre, LocalDateTime.now().minusMinutes(5));

        assertThat(login(nombre, "0000").estado()).isEqualTo(401);

        long despues = intentoRepository.fallosDesde(nombre, LocalDateTime.now().minusMinutes(5));
        System.out.println("VERIFICACION fallos grabados => antes=" + antes + " despues=" + despues);
        assertThat(despues).isEqualTo(antes + 1);
    }

    @Test
    void alSextoIntentoElUsuarioQuedaBloqueado() {
        String nombre = crearUsuaria("bloqueable");

        for (int intento = 1; intento <= ServicioAutenticacion.FALLOS_PARA_BLOQUEAR; intento++) {
            var fallo = login(nombre, "0000");
            System.out.println("VERIFICACION intento fallido " + intento + " => " + fallo.estado());
            assertThat(fallo.estado())
                    .withFailMessage("El intento %d debía ser 401, no bloqueo todavía", intento)
                    .isEqualTo(401);
        }

        var bloqueado = login(nombre, "0000");
        System.out.println("VERIFICACION sexto intento => " + bloqueado.estado() + " "
                + bloqueado.cuerpo() + " Retry-After=" + bloqueado.cabecera("Retry-After").orElse("(sin)"));

        assertThat(bloqueado.estado()).isEqualTo(429);
        assertThat(bloqueado.cuerpo()).contains("DEMASIADOS_INTENTOS");
        assertThat(bloqueado.cabecera("Retry-After")).isPresent();

        // Los segundos van también en el cuerpo, no solo en la cabecera: la pantalla de
        // login muestra una cuenta regresiva y sacar el número de la frase "hay que
        // esperar 5 minuto(s)" sería frágil y absurdo teniendo el dato.
        assertThat(bloqueado.cuerpo())
                .withFailMessage("El 429 no trae reintentarEnSegundos en el cuerpo: la pantalla "
                        + "tendría que deducir la cuenta regresiva del texto")
                .contains("\"reintentarEnSegundos\":");
        assertThat(Integer.parseInt(bloqueado.cabecera("Retry-After").orElseThrow()))
                .isBetween(1, (int) ServicioAutenticacion.VENTANA_DE_BLOQUEO.toSeconds());
    }

    /** Estando bloqueada, ni el PIN correcto entra: es lo que hace útil al bloqueo. */
    @Test
    void bloqueadaNiConElPinCorrecto() {
        String nombre = crearUsuaria("bloqueable-con-pin-bueno");
        for (int i = 0; i < ServicioAutenticacion.FALLOS_PARA_BLOQUEAR; i++) {
            login(nombre, "0000");
        }

        var conPinBueno = login(nombre, PIN_BUENO);

        System.out.println("VERIFICACION PIN correcto estando bloqueada => " + conPinBueno.estado());
        assertThat(conPinBueno.estado()).isEqualTo(429);
    }

    /**
     * Entrar bien reinicia la cuenta.
     *
     * <p>Si el bloqueo contara solo por ventana de tiempo, alguien que falló cuatro
     * veces, entró bien y volvió a equivocarse una vez quedaría bloqueado — a pesar
     * de haber demostrado que es quien dice ser. Aquí se prueba con cuatro fallos,
     * un acierto y cuatro fallos más: ocho fallos en la misma ventana de cinco
     * minutos y aún así sin bloquear.
     */
    @Test
    void unLoginExitosoReiniciaLaCuentaDeFallos() {
        String nombre = crearUsuaria("con-acierto-intermedio");

        for (int i = 0; i < 4; i++) {
            assertThat(login(nombre, "0000").estado()).isEqualTo(401);
        }
        assertThat(login(nombre, PIN_BUENO).estado()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            assertThat(login(nombre, "0000").estado()).isEqualTo(401);
        }

        long segundos = servicioAutenticacion.segundosDeBloqueoRestantes(nombre);
        System.out.println("VERIFICACION 4 fallos + acierto + 4 fallos => bloqueo restante "
                + segundos + " s (debe ser 0)");
        assertThat(segundos).isZero();

        assertThat(login(nombre, PIN_BUENO).estado()).isEqualTo(200);
    }

    @Test
    void elBloqueoEsPorUsuarioYNoGlobal() {
        String bloqueada = crearUsuaria("la-que-se-bloquea");
        String tranquila = crearUsuaria("la-que-no");

        for (int i = 0; i <= ServicioAutenticacion.FALLOS_PARA_BLOQUEAR; i++) {
            login(bloqueada, "0000");
        }

        var otra = login(tranquila, PIN_BUENO);
        System.out.println("VERIFICACION la otra usuaria entra normal => " + otra.estado());
        assertThat(otra.estado()).isEqualTo(200);
    }

    /**
     * Un nombre que no existe también se registra y también se bloquea: si alguien
     * está probando nombres al azar, queda el rastro y se frena igual.
     */
    @Test
    void tambienSeBloqueaUnNombreQueNoExiste() {
        String inventado = "fantasma-" + System.nanoTime();

        for (int i = 0; i < ServicioAutenticacion.FALLOS_PARA_BLOQUEAR; i++) {
            assertThat(login(inventado, "0000").estado()).isEqualTo(401);
        }

        var bloqueado = login(inventado, "0000");
        System.out.println("VERIFICACION nombre inexistente tras 5 fallos => " + bloqueado.estado());
        assertThat(bloqueado.estado()).isEqualTo(429);
    }

    private ClienteHttpDePrueba.Respuesta login(String nombre, String pin) {
        return cliente.post("/api/v1/auth/login",
                "{\"nombre\":\"" + nombre + "\",\"pin\":\"" + pin + "\"}");
    }

    private String crearUsuaria(String etiqueta) {
        String nombre = etiqueta + "-" + System.nanoTime();
        Usuario usuaria = new Usuario();
        usuaria.setNombre(nombre);
        usuaria.setRol(Rol.EMPLEADA);
        usuaria.setPinHash(new BCryptPasswordEncoder().encode(PIN_BUENO));
        usuaria.setActivo(true);
        usuaria.setFechaCreacion(LocalDateTime.now());
        usuarioRepository.save(usuaria);
        return nombre;
    }
}
