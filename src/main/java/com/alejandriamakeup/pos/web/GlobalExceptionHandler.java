package com.alejandriamakeup.pos.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Todos los errores de la API, con una sola forma: <code>{"codigo": …, "error": …}</code>.
 *
 * <p>La uniformidad no es estética. El front ramifica sobre {@code codigo}; el texto de
 * {@code error} está escrito para leerse en pantalla y tiene que poder cambiar sin
 * romper a nadie. Una respuesta sin {@code codigo} obliga al front a mirar el mensaje, y
 * a partir de ahí cualquier mejora de redacción es un cambio incompatible.
 *
 * <p>Para que no pueda divergir, <strong>ningún manejador construye su propia
 * respuesta</strong>: todos pasan por {@link #respuesta}. Y
 * {@code FormaDeLosErroresTest} provoca cada clase de error por HTTP, comprueba la forma,
 * y exige que todo {@code @ExceptionHandler} nuevo tenga su provocación.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final TraductorRestriccionesSqlite traductor;

    public GlobalExceptionHandler(TraductorRestriccionesSqlite traductor) {
        this.traductor = traductor;
    }

    /**
     * Errores previstos: los que la aplicación decide y sabe explicar.
     */
    @ExceptionHandler(ErrorDeAplicacion.class)
    public ResponseEntity<Map<String, Object>> manejarErrorPrevisto(ErrorDeAplicacion ex) {
        log.warn("{} -> {} {}", ex.getCodigo(), ex.getEstado().value(), ex.getMessage());
        return respuesta(ex.getEstado(), ex.getCodigo(), ex.getMessage(), null,
                ex.getCabeceras(), ex.getDatos());
    }

    /**
     * Violaciones de restricción de la base.
     *
     * <p>El handler va sobre {@link DataAccessException} y no sobre
     * {@code DataIntegrityViolationException} a propósito: con SQLite esta última no se
     * dispara nunca. El driver de xerial lanza una {@code SQLiteException} genérica que
     * Hibernate no reconoce como violación de integridad, así que Spring la envuelve en
     * {@code JpaSystemException}. La clasificación real la hace
     * {@link TraductorRestriccionesSqlite} sobre la causa raíz.
     *
     * <p>Si la causa raíz no es una restricción, esto no es un conflicto sino un error:
     * se registra y sale 500.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> manejarErrorDeDatos(DataAccessException ex) {
        return traductor.traducir(ex)
                .map(problema -> {
                    log.warn("Restricción violada: {} {}", problema.codigo(), problema.mensaje());
                    return respuesta(problema.estado(), problema.codigo(), problema.mensaje());
                })
                .orElseGet(() -> {
                    log.error("Error de acceso a datos no clasificado", ex);
                    return errorInterno();
                });
    }

    /** Un cuerpo o un parámetro que no cumplen las anotaciones de validación. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> manejarValidacion(MethodArgumentNotValidException ex) {
        List<String> detalles = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        return respuesta(HttpStatus.BAD_REQUEST, "VALIDACION_FALLIDA",
                "Hay campos con valores inválidos.", detalles, Map.of());
    }

    /**
     * JSON mal formado o de un tipo que no encaja.
     *
     * <p>Salía como 500 y era engañoso: un cuerpo roto es culpa de quien llama, no de la
     * aplicación. Un 500 aquí manda a buscar un fallo del servidor que no existe, y de
     * paso le esconde al cliente que puede arreglarlo él.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> manejarCuerpoIlegible(HttpMessageNotReadableException ex) {
        log.warn("Cuerpo de petición ilegible: {}", ex.getMostSpecificCause().getMessage());
        return respuesta(HttpStatus.BAD_REQUEST, "CUERPO_INVALIDO",
                "El cuerpo de la petición no es JSON válido o no encaja con lo que se espera.");
    }

    /** Un parámetro de ruta del tipo equivocado, como un id que no es número. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> manejarParametroInvalido(
            MethodArgumentTypeMismatchException ex) {
        log.warn("Parámetro '{}' con valor inválido: {}", ex.getName(), ex.getValue());
        return respuesta(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO",
                "El parámetro '" + ex.getName() + "' no tiene un valor válido.");
    }

    /** Una ruta que no corresponde a ningún recurso. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> manejarRecursoNoEncontrado(NoResourceFoundException ex) {
        return respuesta(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "No existe ese recurso.");
    }

    /** Lo que no previó nadie. Se registra completo y sale genérico. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> manejarError(Exception ex) {
        log.error("Error no controlado", ex);
        return errorInterno();
    }

    // ------------------------------------------------------------------ forma

    private ResponseEntity<Map<String, Object>> errorInterno() {
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "Error interno del servidor.");
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String codigo,
                                                          String mensaje) {
        return respuesta(estado, codigo, mensaje, null, Map.of(), Map.of());
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String codigo,
                                                          String mensaje, List<String> detalles,
                                                          Map<String, String> cabeceras) {
        return respuesta(estado, codigo, mensaje, detalles, cabeceras, Map.of());
    }

    /**
     * El único sitio donde se construye un cuerpo de error. Todo manejador pasa por aquí,
     * así que la forma no puede divergir entre unos y otros.
     *
     * <p>{@code codigo} y {@code error} siempre; {@code detalles} y los {@code datos}
     * extra solo cuando aportan algo. Los datos nunca pueden pisar a los dos primeros.
     */
    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String codigo,
                                                          String mensaje, List<String> detalles,
                                                          Map<String, String> cabeceras,
                                                          Map<String, Object> datos) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("codigo", codigo);
        cuerpo.put("error", mensaje);
        if (detalles != null && !detalles.isEmpty()) {
            cuerpo.put("detalles", detalles);
        }
        datos.forEach((clave, valor) -> {
            if (!"codigo".equals(clave) && !"error".equals(clave)) {
                cuerpo.put(clave, valor);
            }
        });

        ResponseEntity.BodyBuilder constructor = ResponseEntity.status(estado);
        cabeceras.forEach(constructor::header);
        return constructor.body(cuerpo);
    }
}
