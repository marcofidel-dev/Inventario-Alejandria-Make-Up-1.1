package com.alejandriamakeup.pos.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Un error previsto, con su estado HTTP y un código que el front puede
 * distinguir sin leer el mensaje.
 *
 * <p>Una sola clase en vez de una jerarquía de excepciones: el
 * {@link GlobalExceptionHandler} la traduce en un solo sitio, y agregar un caso
 * nuevo es una fábrica estática y no otro {@code @ExceptionHandler} que alguien
 * puede olvidar.
 */
public class ErrorDeAplicacion extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;
    private final Map<String, String> cabeceras;
    private final Map<String, Object> datos;

    private ErrorDeAplicacion(HttpStatus estado, String codigo, String mensaje,
                              Map<String, String> cabeceras, Map<String, Object> datos) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
        this.cabeceras = cabeceras;
        this.datos = datos;
    }

    private ErrorDeAplicacion(HttpStatus estado, String codigo, String mensaje,
                              Map<String, String> cabeceras) {
        this(estado, codigo, mensaje, cabeceras, Map.of());
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public Map<String, String> getCabeceras() {
        return cabeceras;
    }

    /**
     * Campos extra que se agregan al cuerpo del error, además de {@code codigo} y
     * {@code error}.
     *
     * <p>Existen para que la pantalla no tenga que deducir nada de un texto. El caso que
     * los motivó es el bloqueo por intentos: la interfaz muestra una cuenta regresiva, y
     * sacar los segundos de la frase "hay que esperar 5 minuto(s)" sería frágil y
     * absurdo teniendo el número.
     */
    public Map<String, Object> getDatos() {
        return datos;
    }

    public static ErrorDeAplicacion noAutenticado() {
        return new ErrorDeAplicacion(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO",
                "Hay que iniciar sesión.", Map.of());
    }

    public static ErrorDeAplicacion credencialesInvalidas() {
        return new ErrorDeAplicacion(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS",
                "Usuario o PIN incorrectos.", Map.of());
    }

    public static ErrorDeAplicacion sinPermiso(String detalle) {
        return new ErrorDeAplicacion(HttpStatus.FORBIDDEN, "SIN_PERMISO", detalle, Map.of());
    }

    /**
     * Una ruta de la API sin regla declarada en {@code ReglasDeAcceso}. Se niega,
     * porque el default es negar, pero es un bug de programación: el mensaje lo
     * dice para que quien lo vea sepa dónde arreglarlo.
     */
    public static ErrorDeAplicacion rutaSinRegla(String metodo, String ruta) {
        return new ErrorDeAplicacion(HttpStatus.FORBIDDEN, "RUTA_SIN_REGLA",
                "La ruta " + metodo + " " + ruta + " no tiene regla de acceso declarada "
                        + "en ReglasDeAcceso. Se niega por defecto.", Map.of());
    }

    public static ErrorDeAplicacion configuracionInicialRequerida() {
        return new ErrorDeAplicacion(HttpStatus.CONFLICT, "CONFIGURACION_INICIAL_REQUERIDA",
                "No hay ningún usuario. Hay que crear la usuaria administradora antes de usar la aplicación.",
                Map.of());
    }

    /**
     * @param segundosRestantes va en la cabecera {@code Retry-After} y también en el
     *        cuerpo, como {@code reintentarEnSegundos}: la pantalla de login muestra una
     *        cuenta regresiva y necesita el número, no la frase
     */
    public static ErrorDeAplicacion demasiadosIntentos(long segundosRestantes) {
        return new ErrorDeAplicacion(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS",
                "Esta cuenta quedó bloqueada por cinco intentos fallidos. Hay que esperar "
                        + Math.max(1, (segundosRestantes + 59) / 60) + " minuto(s).",
                Map.of("Retry-After", String.valueOf(segundosRestantes)),
                Map.of("reintentarEnSegundos", segundosRestantes));
    }

    public static ErrorDeAplicacion conflicto(String codigo, String mensaje) {
        return new ErrorDeAplicacion(HttpStatus.CONFLICT, codigo, mensaje, Map.of());
    }

    public static ErrorDeAplicacion noEncontrado(String mensaje) {
        return new ErrorDeAplicacion(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", mensaje, Map.of());
    }

    public static ErrorDeAplicacion peticionInvalida(String mensaje) {
        return new ErrorDeAplicacion(HttpStatus.BAD_REQUEST, "PETICION_INVALIDA", mensaje, Map.of());
    }
}
