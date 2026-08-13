package com.alejandriamakeup.pos.web;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Traduce una violación de restricción de SQLite a un estado HTTP y un mensaje
 * en español.
 *
 * <p>Existe porque el tipo de excepción de Spring no sirve para clasificar: el
 * driver de xerial lanza una {@link SQLiteException} genérica, Hibernate no la
 * reconoce como violación de integridad y Spring la termina envolviendo en
 * {@code JpaSystemException}. Un {@code @ExceptionHandler} sobre
 * {@code DataIntegrityViolationException} nunca se dispararía. Así que la
 * clasificación se hace sobre la causa raíz, y por el
 * {@link SQLiteErrorCode} tipado en vez de comparando textos.
 *
 * <p>Del mensaje sí hay que leer texto, porque el nombre de lo que colisionó solo
 * viene ahí. SQLite lo reporta de dos formas distintas y ambas ocurren en este
 * esquema:
 *
 * <ul>
 *   <li>por nombre de índice — {@code UNIQUE constraint failed: index 'ux_variante_combinacion'} —
 *       cuando el índice es sobre expresión o parcial;
 *   <li>por lista de columnas — {@code UNIQUE constraint failed: marca.nombre} —
 *       cuando es sobre columnas desnudas.
 * </ul>
 */
@Component
public class TraductorRestriccionesSqlite {

    private static final Pattern NOMBRE_DE_INDICE = Pattern.compile("index '([^']+)'");
    private static final Pattern LISTA_DE_COLUMNAS = Pattern.compile("constraint failed: ([^)]+)");

    /**
     * Mensajes por restricción, con la clave en la forma en que SQLite reporta esa
     * restricción en concreto: nombre de índice para las de expresión y parciales,
     * lista de columnas para las demás. Lo que no esté aquí cae en un mensaje
     * genérico que igual nombra la restricción, así que agregar una entrada mejora
     * el texto pero nunca es obligatorio.
     */
    private static final Map<String, String> MENSAJES = Map.ofEntries(
            // Índices sobre expresión o parciales: SQLite los reporta por nombre.
            // V4 convirtió los de marca, categoría y producto a expresión — con la
            // normalización de tildes — así que sus claves pasaron de lista de
            // columnas a nombre de índice. El test de la Fase 2 lo detectó.
            Map.entry("ux_marca_nombre", "Ya existe una marca con ese nombre."),
            Map.entry("ux_categoria_nombre", "Ya existe una categoría con ese nombre."),
            Map.entry("ux_producto_marca_nombre",
                    "Esa marca ya tiene un producto con ese nombre."),
            Map.entry("ux_variante_combinacion",
                    "Ya existe una variante de ese producto con el mismo tono y tamaño."),
            Map.entry("ux_variante_codigo_barras",
                    "Ese código de barras ya está asignado a otra variante."),
            Map.entry("ux_sesion_caja_unica_abierta",
                    "Ya hay una sesión de caja abierta. Hay que cerrarla antes de abrir otra."),
            // Índices sobre columnas desnudas: SQLite los reporta por lista de columnas.
            Map.entry("usuario.nombre", "Ya existe un usuario con ese nombre."),
            Map.entry("proveedor.nombre", "Ya existe un proveedor con ese nombre."),
            Map.entry("venta.consecutivo", "Ya existe una venta con ese consecutivo."),
            Map.entry("venta.uuid", "Esa venta ya fue registrada."),
            Map.entry("compra.consecutivo", "Ya existe una compra con ese consecutivo."),
            Map.entry("sesion_caja.consecutivo", "Ya existe una sesión de caja con ese consecutivo."));

    /**
     * El resultado de traducir: qué responder, con qué código y qué decir.
     *
     * <p>El {@code codigo} existe para que estas respuestas tengan la misma forma que
     * el resto de los errores de la API. Salían sin él, así que el front no tenía nada
     * en qué ramificar y le tocaba mirar el texto — que está escrito para leerse en
     * pantalla y puede cambiar. Lo detectó el recorrido en vivo, no un test: los tests
     * comprobaban el mensaje y el estado, que sí estaban bien.
     */
    public record Problema(HttpStatus estado, String codigo, String mensaje) {
    }

    /**
     * @return el problema traducido, o vacío si la excepción no viene de una
     *         restricción de SQLite — en cuyo caso es un error de verdad y le
     *         corresponde un 500.
     */
    public Optional<Problema> traducir(Throwable excepcion) {
        return raizSqlite(excepcion).map(this::traducirCodigo);
    }

    private Problema traducirCodigo(SQLiteException sqlite) {
        SQLiteErrorCode codigo = sqlite.getResultCode();
        String mensaje = sqlite.getMessage();

        return switch (codigo) {
            case SQLITE_CONSTRAINT_UNIQUE, SQLITE_CONSTRAINT_PRIMARYKEY ->
                    new Problema(HttpStatus.CONFLICT, "UNICIDAD_VIOLADA", mensajeDeUnicidad(mensaje));
            case SQLITE_CONSTRAINT_FOREIGNKEY ->
                    new Problema(HttpStatus.CONFLICT, "REFERENCIA_EN_USO",
                            "El registro está referenciado por otros datos y no se puede modificar así.");
            case SQLITE_CONSTRAINT_NOTNULL ->
                    new Problema(HttpStatus.BAD_REQUEST, "DATO_OBLIGATORIO", "Falta un dato obligatorio.");
            case SQLITE_CONSTRAINT_CHECK ->
                    new Problema(HttpStatus.BAD_REQUEST, "VALOR_FUERA_DE_RANGO",
                            "Un valor está fuera del rango permitido.");
            default -> new Problema(HttpStatus.CONFLICT, "RESTRICCION_VIOLADA",
                    "La operación viola una regla de la base de datos.");
        };
    }

    private String mensajeDeUnicidad(String mensajeSqlite) {
        String clave = claveDeRestriccion(mensajeSqlite);
        String conocido = MENSAJES.get(clave);
        return conocido != null
                ? conocido
                : "Ya existe un registro con esos datos (" + clave + ").";
    }

    /**
     * El nombre de índice si SQLite lo dio, y si no la lista de columnas. Se prueba
     * el índice primero porque la expresión de columnas también calzaría con el
     * texto {@code index '...'}.
     */
    private String claveDeRestriccion(String mensajeSqlite) {
        Matcher porIndice = NOMBRE_DE_INDICE.matcher(mensajeSqlite);
        if (porIndice.find()) {
            return porIndice.group(1);
        }

        Matcher porColumnas = LISTA_DE_COLUMNAS.matcher(mensajeSqlite);
        return porColumnas.find() ? porColumnas.group(1).trim() : "restricción no identificada";
    }

    /** Recorre la cadena de causas buscando la excepción real del driver. */
    private Optional<SQLiteException> raizSqlite(Throwable excepcion) {
        for (Throwable actual = excepcion; actual != null; actual = actual.getCause()) {
            if (actual instanceof SQLiteException sqlite) {
                return Optional.of(sqlite);
            }
            if (actual.getCause() == actual) {
                break;
            }
        }
        return Optional.empty();
    }
}
