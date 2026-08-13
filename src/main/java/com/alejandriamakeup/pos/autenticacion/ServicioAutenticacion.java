package com.alejandriamakeup.pos.autenticacion;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alejandriamakeup.pos.config.Fechas;
import com.alejandriamakeup.pos.usuarios.Rol;
import com.alejandriamakeup.pos.usuarios.Usuario;
import com.alejandriamakeup.pos.usuarios.UsuarioRepository;
import com.alejandriamakeup.pos.web.ErrorDeAplicacion;

/**
 * Inicio de sesión por PIN, con bloqueo por intentos fallidos.
 *
 * <p>El bloqueo cuenta los fallos <strong>posteriores al último inicio de sesión
 * exitoso</strong> y dentro de la ventana de cinco minutos. Contar solo por
 * ventana de tiempo dejaría a alguien que falló cuatro veces y luego entró bien a
 * un solo intento del bloqueo, lo cual es absurdo: entrar bien demuestra que es
 * quien dice ser.
 *
 * <p>El riesgo que cubre no es un atacante remoto — la app no está expuesta —
 * sino alguien probando PINs de a uno con el equipo delante. Por eso la cuenta
 * está persistida en {@code intento_login} y no en memoria: cerrar y reabrir la
 * aplicación no limpia el bloqueo.
 */
@Service
@Transactional(readOnly = true)
public class ServicioAutenticacion {

    private static final Logger log = LoggerFactory.getLogger(ServicioAutenticacion.class);

    static final int FALLOS_PARA_BLOQUEAR = 5;
    static final Duration VENTANA_DE_BLOQUEO = Duration.ofMinutes(5);

    private final UsuarioRepository usuarioRepository;
    private final IntentoLoginRepository intentoRepository;
    private final PasswordEncoder codificador = new BCryptPasswordEncoder();

    public ServicioAutenticacion(UsuarioRepository usuarioRepository,
                                 IntentoLoginRepository intentoRepository) {
        this.usuarioRepository = usuarioRepository;
        this.intentoRepository = intentoRepository;
    }

    public boolean requiereConfiguracionInicial() {
        return usuarioRepository.count() == 0;
    }

    /**
     * Crea la usuaria administradora en el primer arranque. Se cierra sola: con un
     * usuario ya existente responde 409, así que no hay forma de usarla como puerta
     * trasera más adelante.
     */
    @Transactional
    public Usuario crearAdministradoraInicial(String nombre, String pin) {
        if (!requiereConfiguracionInicial()) {
            throw ErrorDeAplicacion.conflicto("CONFIGURACION_INICIAL_YA_HECHA",
                    "Ya existe al menos un usuario: la configuración inicial ya se hizo.");
        }

        Usuario duena = new Usuario();
        duena.setNombre(nombre);
        duena.setRol(Rol.DUENA);
        duena.setPinHash(codificador.encode(pin));
        duena.setActivo(true);
        duena.setFechaCreacion(Fechas.ahora());

        Usuario guardada = usuarioRepository.save(duena);
        log.info("Configuración inicial: creada la usuaria administradora '{}'", nombre);
        return guardada;
    }

    /**
     * Verifica nombre y PIN, registrando el intento en cualquier caso.
     *
     * <p><strong>{@code noRollbackFor} es imprescindible, no una optimización.</strong>
     * Este método graba el intento fallido y acto seguido lanza la excepción que
     * reporta el fallo; sin esta anotación, esa excepción haría rollback de la
     * transacción y se llevaría consigo el registro del intento. El contador nunca
     * subiría de cero y el bloqueo no se activaría jamás — un fallo silencioso
     * perfecto, porque la respuesta 401 al usuario se ve idéntica.
     *
     * <p>Lo que uno haría por reflejo, {@code REQUIRES_NEW} para grabar en su propia
     * transacción, aquí no sirve: pediría una segunda conexión, y con
     * {@code maximum-pool-size: 1} eso se cuelga hasta el timeout.
     *
     * @throws ErrorDeAplicacion 429 si está bloqueado, 401 si las credenciales no
     *         sirven
     */
    @Transactional(noRollbackFor = ErrorDeAplicacion.class)
    public Usuario autenticar(String nombre, String pin) {
        long segundosDeBloqueo = segundosDeBloqueoRestantes(nombre);
        if (segundosDeBloqueo > 0) {
            // No se registra este intento: si contara, cada reintento durante el
            // bloqueo lo prolongaría y el usuario legítimo nunca podría volver.
            log.warn("Intento de '{}' rechazado por bloqueo, faltan {} s", nombre, segundosDeBloqueo);
            throw ErrorDeAplicacion.demasiadosIntentos(segundosDeBloqueo);
        }

        Optional<Usuario> encontrado = usuarioRepository.findByNombre(nombre)
                .filter(Usuario::isActivo)
                .filter(usuario -> codificador.matches(pin, usuario.getPinHash()));

        registrarIntento(nombre, encontrado.isPresent());

        return encontrado.orElseThrow(() -> {
            log.warn("Credenciales inválidas para '{}'", nombre);
            return ErrorDeAplicacion.credencialesInvalidas();
        });
    }

    /** Segundos que faltan para que se libere el bloqueo; 0 si no está bloqueado. */
    public long segundosDeBloqueoRestantes(String nombre) {
        LocalDateTime desde = pisoDeConteo(nombre);

        if (intentoRepository.fallosDesde(nombre, desde) < FALLOS_PARA_BLOQUEAR) {
            return 0;
        }

        LocalDateTime primerFallo = intentoRepository.primerFalloDesde(nombre, desde)
                .orElse(Fechas.ahora());
        LocalDateTime seLibera = primerFallo.plus(VENTANA_DE_BLOQUEO);
        long segundos = Duration.between(Fechas.ahora(), seLibera).toSeconds();
        return Math.max(0, segundos);
    }

    /**
     * Desde cuándo cuentan los fallos: lo más reciente entre el inicio de la
     * ventana y el último inicio de sesión exitoso.
     */
    private LocalDateTime pisoDeConteo(String nombre) {
        LocalDateTime inicioDeVentana = Fechas.ahora().minus(VENTANA_DE_BLOQUEO);
        return intentoRepository.ultimoExitoDe(nombre)
                .filter(ultimoExito -> ultimoExito.isAfter(inicioDeVentana))
                .orElse(inicioDeVentana);
    }

    private void registrarIntento(String nombre, boolean exito) {
        intentoRepository.save(IntentoLogin.builder()
                .nombre(nombre)
                .exito(exito)
                .fecha(Fechas.ahora())
                .build());
    }
}
