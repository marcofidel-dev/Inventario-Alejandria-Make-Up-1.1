# Alejandria MakeUp — Sistema de Inventario y POS

App de escritorio para una tienda de maquillaje en Colombia. Un solo PC, sin
hardware adicional (sin lector de código de barras, sin impresora térmica).
Spring Boot + SQLite + React, empaquetada con `jpackage`.

La especificación completa está en `docs/ESPECIFICACION.md`. Léela antes de
tocar cualquier módulo de dominio.

Todo el código, nombres de tablas, mensajes y comentarios en español.

---

## Invariantes — violarlos rompe el sistema en silencio

### Dinero
Todo monto es `long` en pesos colombianos enteros. Nunca `BigDecimal`, nunca
`double`. SQLite no tiene tipo `DECIMAL` y guarda los decimales como punto
flotante.

### Inventario
- El stock **es** `SUM(cantidad)` sobre `MovimientoInventario`. Nunca un campo
  que se actualiza.
- `MovimientoInventario` y `MovimientoCaja` son **append-only**. Nunca `UPDATE`,
  nunca `DELETE`. Un error se corrige con un movimiento de ajuste.

### Catálogo
- **Los productos y variantes se crean únicamente al registrar una compra o en la
  carga inicial. El catálogo administra existentes: precio, stock mínimo, activación
  y correcciones. Nunca crea.** Un producto sin costo real permite congelar costo 0
  en una venta y corromper el margen histórico.
- El catálogo **solo lista variantes con historial** — al menos un movimiento en el
  ledger. Una variante creada dentro de un borrador de compra no aparece hasta que la
  compra se recibe, y si el borrador se descarta no aparece nunca. Lo mismo vale para
  el buscador del POS: no se puede vender lo que nunca entró.
- Agotada y nunca recibida dan **las dos stock 0**, y el número no las distingue: la
  distinción viaja en `VarianteDto.conHistorial`. En el front, `catalogo.filas` es la
  lista corta —la de las pantallas que muestran y venden— y `catalogo.todas` la
  excepción, solo para las dos pantallas por donde entra la mercancía y para nombrar
  una variante ya referenciada por una compra. Los `POST` del backend se quedan: los
  usa el flujo de compra.

### Ventas
- `VentaItem` congela precio, costo y descripción al momento de la venta. Sin
  eso, cambiar un precio corrompe retroactivamente todas las métricas históricas.
- Ninguna venta existe fuera de una sesión de caja abierta.
- Los consecutivos salen de la tabla `Consecutivo`, no de `AUTOINCREMENT`:
  SQLite puede saltar números si una transacción hace rollback.

### Compras
- El **costo promedio se recalcula replicando el ledger en orden**, nunca con una
  fórmula agregada. `Σ(cantidad × costo) / Σ(cantidad)` está mal: con una venta
  entre dos compras da 7.000 donde el correcto es 8.333, y al anular una compra
  íntegra divide por cero. Las unidades ya vendidas no pueden seguir pesando en el
  costo de lo que queda. Una sola función —`ServicioCostoPromedio.recalcular()`—
  para recibir y para anular; si hubiera una fórmula "hacia adelante" y otra "hacia
  atrás", algún día dirían cosas distintas.
- Una compra **ANULADA nunca ocurrió**: el replay salta sus dos movimientos, el
  `COMPRA` y el `ANULACION`. Como netean a cero el stock no cambia por saltarlos;
  lo que cambia es el promedio, y ahí está todo el punto.
- **Anular puede dejar el stock negativo y no se bloquea.** Si la mercancía nunca
  llegó, el negativo es verdad: dice que se vendió de más. La pantalla lo advierte
  antes de confirmar nombrando la variante, y el catálogo lo marca distinto de
  "stock bajo" — uno dice "hay que reponer", el otro "falta averiguar algo".
- **Descartar y anular son actos distintos**, nunca juntos ni con el mismo aspecto:
  descartar solo aplica a un BORRADOR y no revierte nada; anular solo a una
  RECIBIDA y devuelve stock. Los dos exigen motivo, y lo exige también un `CHECK`.
- **Compras no escribe en caja.** La pantalla pregunta cómo se pagó y, solo si fue
  efectivo del cajón, hace una segunda llamada independiente al endpoint de
  movimientos de caja. Preguntarlo no es burocracia: un pago en efectivo sin
  registrar deja el cierre de ese día con un faltante que nadie sabe explicar.
- El **total lo calcula el servidor** sumando las líneas; la petición no tiene campo
  para mandarlo. La pantalla muestra después el que devolvió el servidor.
- Las previas de recepción y anulación son **informativas**: sus números no vuelven
  nunca al servidor, que recalcula con el stock del instante de confirmar.

### Caja
- **Cierre a ciegas**: primero se ingresa el conteo físico, y solo después el
  sistema revela esperado y diferencia. Nunca al revés. El conteo y el cierre son
  **la misma llamada**: no hay un paso previo donde el sistema pueda adelantar el
  esperado, porque no existe el endpoint que lo daría.
- **El front descarta el monto de los movimientos en el límite de la API**
  (`api/endpoints.js`), no al pintar. El backend oculta la base inicial, pero el
  front la conoce —él mismo la envió al abrir— y una lista que acumule los montos
  reconstruye el esperado al centavo. Descartarlo en el límite hace que la pantalla
  no pueda filtrarlo aunque quiera: nunca lo ve.
- **Una sesión cerrada es inmutable**: sus montos, fechas y usuarios no se modifican
  nunca. Las explicaciones se agregan como notas append-only en `nota_sesion_caja`,
  jamás editando la sesión. `sesion_caja.observaciones` quedó **en desuso** desde V6:
  viajaba dentro de `CerrarSesionPeticion`, o sea antes de saber si había algo que
  observar, y para cuando se sabe la sesión ya está cerrada.
- **La nota no es obligatoria y se pide después de revelar la diferencia.** Pedirla
  antes captura ruido —se escribe "normal" y luego aparece el faltante—, y un campo
  obligatorio que casi siempre sobra deja de leerse en serio justo el día que
  importa. El historial marca "sin explicar" la sesión con diferencia y sin notas:
  rendición de cuentas visible, sin obligar a nadie a rellenar.
- La diferencia se presenta **como un hecho, no como una acusación**: sin fondo de
  alarma, sin `role="alert"`, sin lenguaje de error. Sobrante y faltante sí se
  distinguen (`--diferencia-favor` / `--diferencia-contra`), porque eso cambia qué
  hay que buscar.
- Una devolución sobre una sesión cerrada golpea la sesión actual.

### Recibos
- El PDF dice **"RECIBO"** o **"COMPROBANTE DE VENTA"**. Nunca "FACTURA": no
  está validado por la DIAN.
- Se genera **después** del commit de la venta, nunca dentro de la transacción.
  La venta es la verdad; el PDF es derivado.
- Apache PDFBox. Nunca iText: la versión gratuita es AGPL y la app se distribuye.

### Rutas
- Base de datos y recibos viven en `%APPDATA%\AlejandriaMakeUp\`. **Nunca** en
  la carpeta de instalación: la primera actualización borraría el inventario
  del negocio.
- Respaldos con `VACUUM INTO`. Nunca copiar el `.db` en caliente — con WAL
  activo la copia puede salir corrupta.

### Conexiones
Pool de 1 conexión: ningún método `@Transactional` puede pedir un
`dataSource.getConnection()` adicional. Se cuelga y muere por timeout. Todo
acceso dentro de una transacción va por el `EntityManager`.

Lo que debe persistir aunque la operación falle va con `noRollbackFor`, nunca con
`REQUIRES_NEW`: con pool de 1 conexión, `REQUIRES_NEW` se cuelga. Aplica a
intentos de login y a cualquier registro de auditoría futuro.

### Errores de restricción
SQLite: una violación de índice único **NO** llega como
`DataIntegrityViolationException`. El driver de xerial lanza `SQLiteException`
genérica, Hibernate no la reconoce y Spring la envuelve en `JpaSystemException`.
El `GlobalExceptionHandler` debe detectar la violación inspeccionando la causa
raíz, no confiando en el tipo de excepción de Spring.

### Forma de los errores
**Toda** respuesta de error de la API tiene la misma forma: `{codigo, error}`, más
`detalles` opcional y campos extra opcionales como `reintentarEnSegundos`. Todos los
`@ExceptionHandler` pasan por un único constructor privado para que la forma no
pueda divergir. El front **ramifica sobre `codigo`, nunca sobre el texto**: el
mensaje está escrito para mostrarse tal cual y por eso puede cambiar de redacción
sin aviso. Los tests afirman la forma, no solo el mensaje.

### Interfaz
`src/estilos/tokens.css` es la única fuente de color, tipografía, espaciado y
movimiento. **Ningún componente escribe un valor literal.** Lo verifica
`npm run guardas`, encadenado al `build`: todas estas reglas protegen fallas que
**no se notan mirando la pantalla**.

- **Texto blanco sobre los rosas de la marca es ilegible**: los cuatro dan entre
  1.16 y 1.87 de contraste. Sobre marca va siempre `--texto-sobre-marca`. La guarda
  incluye un *tripwire invertido*: si algún rosa llegara a superar 4.5 contra
  blanco, falla pidiendo revisar la regla — avisa cuando cambia el supuesto, no
  solo cuando se rompe la regla.
- **Los rosas no comunican estado.** Entre sí se separan 1.11: a un metro de la
  pantalla son el mismo color, así que no pueden distinguir un error de un éxito.
  El estado va con los semánticos (`--error`, `--exito`, `--alerta`, `--info`).
- **El rosa vive en la identidad** — encabezado, navegación, fila seleccionada,
  botón principal. Tablas y formularios se quedan neutros: ocho horas de pantalla
  rosa saturado cansan y tapan lo que importa, y si el rosa fuera fondo dejaría de
  significar "esto se puede pulsar".
- **Nada por encima de 240ms**, y nada animado en el camino del cobro. De ahí que
  no haya indicadores giratorios: una vuelta creíble necesita cerca de un segundo,
  cuatro veces el techo. El botón lo dice con palabras ("Guardando…") y además se
  lee sin interpretar.
- **Fuentes por `@fontsource`, empaquetadas con la app. Nunca un CDN**: el día que
  se caiga el internet la tienda tiene que seguir abriendo. Solo los pesos que
  declara `tokens.css` (400/500/600 de UI, 500 de monospace), y `latin-ext` además
  de `latin` — es la que trae tildes y eñe. Una fuente que no cargó no da error: el
  navegador sustituye en silencio.
- **Iconos vectoriales** (`lucide-react`). Ninguna imagen rasterizada: un `.png` se
  ve borroso en cuanto cambia la escala de Windows.
- **Marca y categoría se eligen de un desplegable, con opción de crear una nueva en
  el mismo sitio. Nunca texto libre.** Es lo que de verdad evita duplicados: el
  índice de V4 atrapa las variaciones de tilde y mayúscula, pero "Loreal" y
  "L'Oréal Paris" normalizados siguen siendo cadenas distintas y ningún índice
  puede atraparlas. `catalogo/normalizar.js` es el gemelo de `NombreNormalizado.de()`
  —NFD, quitar `\p{M}`, mayúsculas— y ninguno de los dos dobla apóstrofos ni
  puntuación.
- **Una `var(--…)` inexistente no da error**: la propiedad no se aplica y el
  elemento queda sin estilo. La guarda comprueba que toda variable usada exista.

### React
StrictMode **invoca dos veces las funciones actualizadoras de estado** en
desarrollo. Ningún efecto secundario dentro de un `setEstado(fn)`: un `POST`
disparado ahí se envía dos veces y en pantalla no se nota. Lo que decide si enviar
va en un `ref`, que además no depende del agrupado de renders.

---

## SQLite — configuración obligatoria

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${APP_DB_PATH}?foreign_keys=on&journal_mode=WAL&busy_timeout=5000
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      connection-timeout: 3000
      leak-detection-threshold: 10000
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: validate
```

Dependencias: `org.xerial:sqlite-jdbc` + `org.hibernate.orm:hibernate-community-dialects`

`foreign_keys` viene **desactivado** por defecto en SQLite, y los parámetros de
la URL JDBC fallan en silencio si están mal escritos. Verificar con un test que
`PRAGMA foreign_keys` devuelva 1 y `journal_mode` devuelva `wal` en ejecución.

---

## Esquema

Flyway desde V1, nunca `ddl-auto: update`. El esquema se diseña **completo**
antes de la primera migración: SQLite no permite cambiar el tipo de una columna
ni agregar `FOREIGN KEY`, `CHECK` o `UNIQUE` a una columna existente, y
`DROP COLUMN` falla si la columna es PK, tiene UNIQUE, está indexada o la
referencia una FK.

---

## Convenciones

Esta es una app local de un solo usuario. Se descartan a propósito:

- JWT, OAuth2, CORS, rate limiting → PIN con BCrypt y dos roles (Administrador, colaborador)
- Paginación en listados → el catálogo se carga completo al front y se filtra ahí

Se conservan: `GlobalExceptionHandler` con `@RestControllerAdvice`, DTOs
separados de entidades, validación con `@Valid` en DTOs, `@Transactional` en
escrituras, `@Getter @Setter` en entidades (nunca `@Data`), logging con SLF4J.

En el front, Vite + React sin router (una variable de estado `vista` alcanza para
una ventana sin barra de direcciones) y sin librería de estado. `api/cliente.js` es
el único sitio que habla HTTP, y distingue **fallo de red** de **error del
servidor**: en pantalla no son lo mismo — uno dice "revisa el cable", el otro "el
programa falló".

### Qué se prueba
Lo que al romperse **no se nota**, o lo que verificar a mano cuesta mucho. Nada de
snapshots ni de tests de apariencia. Y para cada regla crítica se demuestra que la
prueba **falla cuando la regla se rompe**, no solo que pasa cuando se cumple: una
prueba en verde puede estar afirmando algo que se cumple por casualidad. Eso está
automatizado en el front con `npm run guardas:romper` y `npm run test:romper`, que
mutan el código, exigen que caiga exactamente la guarda o la prueba que dice
cubrirlo, y restauran en `finally`.
