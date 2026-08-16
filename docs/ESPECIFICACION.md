# Alejandria MakeUp — Sistema de Inventario y POS
### Especificación consolidada

---

## 1. Stack y forma de entrega

| Elemento | Decisión |
|---|---|
| Backend | Spring Boot (Java 17+ / 21) |
| Base de datos | SQLite (archivo local) |
| Frontend | React |
| Forma | App de escritorio, un solo PC |
| Empaquetado | React compilado en `src/main/resources/static` → fat JAR → `jpackage` |
| Instalador | `.exe` con JRE incluido (la usuaria nunca instala Java) |
| Arranque | Navegador en modo app (`--app=http://localhost:8080`) |
| Bind | `0.0.0.0` → acceso desde celular por wifi de la tienda |
| Hardware adicional | Ninguno (sin lector de código de barras, sin impresora térmica) |

---

## 2. Configuración crítica de SQLite

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${APP_DB_PATH}?foreign_keys=on&journal_mode=WAL&busy_timeout=5000
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: validate
```

**Dependencias:** `org.xerial:sqlite-jdbc` + `org.hibernate.orm:hibernate-community-dialects`

**Reglas no negociables:**

- `foreign_keys=on` — SQLite las trae **desactivadas** por defecto.
- **Todo el dinero como `long`** en pesos enteros. Nunca `BigDecimal`: SQLite no tiene tipo `DECIMAL` y la afinidad NUMERIC guarda decimales como punto flotante.
- Flyway desde V1. Nunca `ddl-auto: update`.
- **El esquema completo se diseña antes de la primera migración.** SQLite no permite cambiar el tipo de una columna, ni agregar FK, CHECK o UNIQUE a una columna existente. `DROP COLUMN` existe desde 3.35 pero falla si la columna es PK, tiene UNIQUE, está indexada o la referencia una FK.

---

## 3. Infraestructura — hacer antes que cualquier pantalla

**Ubicación de datos**

- Base de datos: `%APPDATA%\AlejandriaMakeUp\data.db` — **jamás** en la carpeta de instalación (la primera actualización borraría el inventario).
- Recibos: `%APPDATA%\AlejandriaMakeUp\recibos\`
- Ambas rutas dentro de una carpeta sincronizada con Google Drive u OneDrive.

**Respaldo automático**

- `VACUUM INTO '<ruta>/backup_YYYY-MM-DD.db'` programado con `@Scheduled`.
- Rotación de 30 días.
- **Nunca copiar el archivo `.db` en caliente** — con WAL activo la copia puede salir corrupta.
- La carpeta de recibos se respalda sola por la sincronización en nube.

---

## 4. Modelo de datos — 18 tablas

> Las 13 de la lista original más `Usuario`, `Cliente`, `ConteoDenominacion` (V2), `IntentoLogin` (V3) y `Configuracion` (V1). El esquema aplicado es el de las migraciones; si esta lista y una migración discrepan, manda la migración.

**Catálogo**
1. `Marca`
2. `Categoria`
3. `Producto` — nombre, marca, categoría. **Sin stock ni precio.**
4. `Variante` — la unidad real de todo. `producto_id`, tono, tamaño, código de barras, `precio_venta`, `costo_promedio`, `stock_minimo`, `fecha_vencimiento`, `pao_meses`

**Inventario**

5. `MovimientoInventario` — **append-only**. `variante_id`, tipo (`CARGA_INICIAL` / `COMPRA` / `VENTA` / `AJUSTE` / `ANULACION` / `MERMA`), cantidad con signo, `costo_unitario`, `compra_id` / `venta_id` según el origen, `usuario_id`, fecha, motivo. `CARGA_INICIAL` se agregó en V4 reconstruyendo la tabla, porque en SQLite un `CHECK` no se puede alterar. Una variante admite **una sola** carga inicial; lo que venga después va por ajuste, que deja constancia del motivo

**Ventas**

6. `Venta` — uuid, consecutivo, `sesion_caja_id` **NOT NULL**, fecha, total, descuento, método de pago, estado, `ruta_recibo` (relativa)
7. `VentaItem` — `variante_id`, cantidad, `precio_unitario_congelado`, `costo_unitario_congelado`, `descripcion_congelada`

**Compras**

8. `Proveedor`
9. `Compra` — factura, fecha, estado
10. `CompraItem` — variante, cantidad, costo

**Caja**

11. `SesionCaja` — consecutivo, usuario y fecha de apertura, `base_inicial`, usuario y fecha de cierre, `efectivo_contado`, `efectivo_esperado`, `diferencia`, estado (`ABIERTA` / `CERRADA`), observaciones
12. `MovimientoCaja` — **append-only**. `sesion_id`, tipo (`VENTA_EFECTIVO` / `RETIRO` / `INGRESO` / `GASTO` / `DEVOLUCION`), monto con signo, `venta_id` si aplica, concepto, fecha
13. `ConteoDenominacion` — `sesion_id`, denominación y cantidad. El conteo del cierre a ciegas, billete por billete, en vez de un total suelto.

**Usuarios y acceso**

14. `Usuario` — nombre, `pin_hash` (BCrypt), rol (`DUENA` / `EMPLEADA`), activo, fecha de creación. No se borra nunca: se desactiva. No se puede desactivar la **última DUENA activa**, porque dejaría la tienda sin quien administre el sistema y sin forma de arreglarlo desde la app
15. `IntentoLogin` — nombre, éxito, fecha. Sostiene el bloqueo tras 5 fallos durante 5 minutos. Se escribe con `noRollbackFor`: el intento fallido tiene que persistir aunque la autenticación lance

**Soporte**

16. `Consecutivo` — contador por tipo de documento (`VENTA` / `COMPRA` / `SESION_CAJA`), incrementado dentro de la misma transacción. El `AUTOINCREMENT` de SQLite puede saltar números si hay rollback.
17. `Cliente` — nombre, whatsapp, notas, fecha de creación. La tabla existe desde V2 para no reconstruirla después, y `venta.cliente_id` la referencia. **Sin CRUD, sin endpoints y sin flujo: hoy no hay forma de crear una clienta ni de asociarla a una venta.** Ver §11.1
18. `Configuracion` — `clave` / `valor`. Creada en V1, antes del dominio

---

## 5. Módulos funcionales

1. **Catálogo** — productos, variantes, marcas, categorías
2. **Punto de venta** — cobro, cálculo de vueltos, método de pago
3. **Compras a proveedores** — recepción de mercancía
4. **Inventario** — consulta de stock, ajustes, alertas de stock mínimo
5. **Caja** — apertura, cierre, arqueo
6. **Métricas de ventas**
7. **Recibos PDF**

---

## 6. Reglas de negocio invariantes

**Inventario**
- El stock **es** `SUM(cantidad)` sobre `MovimientoInventario`. Nunca un campo que se actualiza. Se permite una columna denormalizada por velocidad, pero la fuente de verdad es el log.
- Nunca `UPDATE` ni `DELETE` sobre movimientos. Un error se corrige con un movimiento de ajuste.
- La recepción de una compra recalcula el `costo_promedio` ponderado de la variante y genera sus movimientos.

**Catálogo**
- **Los productos y variantes se crean únicamente al registrar una compra o en la carga inicial. El catálogo administra existentes: precio, stock mínimo, activación y correcciones. Nunca crea. Un producto sin costo real permite congelar costo 0 en una venta y corromper el margen histórico.**
- El catálogo y el buscador del POS **solo listan variantes con al menos un movimiento en el ledger**. Una variante creada dentro de un borrador de compra aparece cuando la compra se recibe; si el borrador se descarta, nunca. Los endpoints `POST` siguen existiendo — los usa el flujo de compra —, lo que se quita es la entrada desde la pantalla de catálogo.
- Agotada y nunca recibida dan las dos stock 0. La distinción viaja en `VarianteDto.conHistorial`, no en el número.

**Ventas**
- Precio, costo y descripción se **congelan** en `VentaItem` al momento de la venta. Sin esto, cualquier cambio de precio corrompe retroactivamente todas las métricas históricas.
- **Ninguna venta existe fuera de una sesión de caja abierta.** Sin caja abierta, el botón de cobrar está deshabilitado.
- Consecutivo sin huecos, vía tabla `Consecutivo`.

**Caja**
- Efectivo esperado = `base_inicial + SUM(movimientos)`.
- **Cierre a ciegas:** primero se ingresa el conteo físico, y solo entonces el sistema revela esperado, contado y diferencia. Nunca al revés.
- Solo el efectivo toca el cajón. Datáfono, Nequi, Daviplata y transferencias entran en la sesión pero se concilian aparte. El cierre muestra desglose por método.
- Al cerrar se congelan `efectivo_esperado` y `diferencia`. Una sesión cerrada es inmutable.
- Una sola sesión abierta a la vez. Si al abrir existe una de un día anterior, se fuerza su cierre con la fecha real.
- Una devolución sobre una venta de sesión cerrada golpea la **sesión actual**, nunca la histórica.
- El cierre registra cuánto se retira y cuánto queda como base del día siguiente.
- Conteo por denominación ($100.000, $50.000, $20.000, $10.000, $5.000, $2.000, $1.000 y monedas) en vez de un total suelto.

---

## 7. Recibos PDF

- **Apache PDFBox** (Apache 2.0). **No usar iText** — la versión gratuita es AGPL y obliga a liberar el código de una app distribuida.
- Ancho fijo, **alto variable**: se calculan las líneas primero y con eso se construye el `PDRectangle`.
- 80mm ≈ 227 pt; área útil ≈ 204 pt. Courier 8pt = ancho de carácter 4.8 pt → **42 caracteres por línea** (48 a 7pt).
- Courier sin embeber fuente. WinAnsiEncoding cubre tildes y ñ.
- API de PDFBox 3: `new PDType1Font(Standard14Fonts.FontName.COURIER)` — los tutoriales de 2.x usan el campo estático que ya no existe.
- Nombres largos: descripción en su propia línea, cantidad × precio = total alineado a la derecha en la siguiente.
- **Se genera después del commit de la venta**, nunca dentro de la transacción. Si falla, se registra y se ofrece regenerar. La venta es la verdad; el PDF es derivado.
- Estructura: `recibos/2026/08/V-2026-000123.pdf` — partición por año y mes.
- En `Venta` se guarda la **ruta relativa**.
- El documento dice **"RECIBO"** o **"COMPROBANTE DE VENTA"**, nunca "FACTURA".
- Generación detrás de una interfaz `GeneradorComprobante` → implementación `PdfReciboLocal` hoy, adaptador DIAN mañana sin reescribir el sistema.

---

## 8. Convenciones de código

**Se conserva**
- `GlobalExceptionHandler` global
- Flyway
- DTOs separados de entidades, validación con `@Valid` en DTOs
- `@Transactional(readOnly = true)` a nivel de clase, `@Transactional` en escrituras
- `@Getter @Setter` en entidades, nunca `@Data`
- Logging con SLF4J

**Se descarta por ser app local de un solo usuario**
- JWT, OAuth2, CORS, rate limiting → PIN con BCrypt y dos roles
- Paginación en listados → se carga el catálogo completo al front y se filtra ahí (búsqueda instantánea en el mostrador)

---

## 9. Interfaz y sistema de diseño

`frontend/src/estilos/tokens.css` es la **única fuente** de color, tipografía, espaciado y movimiento. Ningún componente escribe un valor literal. Las reglas de color, movimiento, fuentes e iconos están en `CLAUDE.md` como invariantes, porque violarlas no se nota mirando la pantalla; aquí queda el resto de las decisiones de interfaz.

**Arquitectura del front**
- Vite + React, **sin router**: es una ventana sin barra de direcciones ni botón de atrás, y una variable de estado `vista` en el armazón alcanza. Coherente con lo que el proyecto ya descartó por ser app local (JWT, CORS, paginación).
- `api/cliente.js` es el único sitio que habla HTTP. Traduce el error a `{codigo, mensaje, detalles, estado, datos}` y distingue **fallo de red** (el `fetch` rechaza) de **error del servidor** (respuesta 5xx). Ante 401 devuelve al login, sin dejar la pantalla esperando datos que no van a llegar.
- Los `permisos` vienen calculados por el backend en la respuesta del login; el front **no reimplementa la tabla de permisos**, solo pregunta. `puede()` decide qué se **dibuja**: esconder un botón no protege nada, solo evita ofrecer algo que va a dar 403.
- El catálogo se carga **una vez** y se filtra en memoria, con la clave de búsqueda precalculada por variante. Ni una llamada por tecla: eso funciona en desarrollo con tres productos y se cae en el mostrador con el inventario real.
- "Stock bajo" es `stock < stockMinimo`, el mismo criterio que `VarianteRepository.bajoMinimo()`, para que la pantalla y el backend no puedan discrepar. Agotado y bajo el mínimo no son lo mismo.

**Login**
- Se **elige el nombre de una lista** (`GET /api/v1/auth/perfiles`, público, solo nombres). Sin el rol: no le sirve a quien entra y diría a cualquiera cuál cuenta administra el sistema. Teclear el nombre sería una vía directa al bloqueo, porque un nombre mal escrito da 401 y cuenta como intento.
- PIN de 4 dígitos con **envío automático al cuarto**, sin botón "Entrar": en una caja se entra varias veces al día y ese clic sobra.
- El **teclado físico es el mecanismo** —dígitos, retroceso, Enter, Escape—; el numpad en pantalla es un agregado, porque si el PC no es táctil tocar botones con el ratón es más lento que teclear.
- Al fallar, **el mismo mensaje siempre**, sin distinguir PIN incorrecto de usuario inexistente. Se limpia el PIN, vuelve el foco y **no se deselecciona el nombre**: obligar a elegirlo otra vez tras cada error es castigar dos veces el mismo dedo torcido.
- **Cuando el bloqueo se activa, la pantalla lo dice.** Aviso con el texto del backend y **cuenta regresiva visible**, alimentada por `reintentarEnSegundos` del cuerpo del 429 — no deducida del texto. Los controles quedan deshabilitados y se reactivan solos al llegar a cero. Sin esto, quien se equivoca cinco veces cree que el programa se dañó y reinicia el equipo o llama a alguien.

**Configuración inicial**
- Nombre y PIN **con confirmación**. No es ceremonia: no hay ningún mecanismo para restablecer un PIN, así que un dedo torcido en la única cuenta administradora dejaría a la tienda afuera del sistema para siempre. Es el mismo agujero que cierra la guarda de la última DUENA activa, entrando por otra puerta.

**Carga inicial de existencias**
- Es la pantalla con la que se mete el inventario real durante horas, así que se diseña **para no tocar el ratón**: el foco se encadena variante → cantidad → costo, y Enter en el costo agrega línea con el foco ya puesto en su primer campo.
- El lote se envía completo al final porque el backend es todo-o-nada. Si una línea falla se marca **esa** línea, con el mensaje del backend que nombra la variante: volver a teclear cuarenta líneas por un error en la treinta y ocho sería imperdonable.

**Estados que hay que resolver, no solo el camino feliz**

| Estado | Qué se ve |
|---|---|
| Catálogo vacío | Por dónde empezar y el botón para crear el primer producto. No un mensaje triste: que está vacío ya se ve |
| Sin conexión | "No hay conexión con el servidor" y reintentar |
| Error del servidor | "El servidor falló" con el mensaje del backend. Distinto del anterior |
| Cuenta bloqueada | Cuenta regresiva visible, controles deshabilitados, reactivación automática |
| Guardando | Botón deshabilitado mientras hay una petición en vuelo. Un doble envío crea dos marcas, o dos cargas iniciales de las que la segunda revienta con 409 |
| 409 del backend | El mensaje tal cual, ramificando sobre `codigo`: `NOMBRE_DUPLICADO` bajo el campo del nombre, `VALIDACION_FALLIDA` reparte sus `detalles` por campo, `CARGA_INICIAL_YA_REGISTRADA` en la línea culpable, el resto como aviso del formulario |

---

## 10. Decisiones pendientes

| Tema | Por qué importa |
|---|---|
| **Situación fiscal ante la DIAN** | En 2026 el documento equivalente POS electrónico es obligatorio para buena parte de los comerciantes. Un PDF local no tiene validez fiscal. Puede que no le aplique si está en régimen no responsable de IVA bajo los topes — confirmar con el contador o en el portal de la DIAN. |
| **¿Segunda caja en el futuro?** | Reabriría toda la decisión de arquitectura. SQLite sobre carpeta compartida en red no es opción. |
| ~~**Separación de permisos dueña / empleada**~~ | **Resuelto:** dos roles en `Usuario`, permisos por rol en `PermisosPorRol` (la EMPLEADA por lista explícita de lo permitido, no de lo prohibido), impuestos por un interceptor que niega por defecto. |

---

## 11. Vacíos detectados — revisar antes de escribir la V1

### 11.1 Afectan el esquema → decidir AHORA

En SQLite agregar una FK a una tabla existente obliga a reconstruirla. Todo lo de esta lista debe estar resuelto antes de la primera migración.

- **Clientes — resuelto a medias, y hay que decidir.** La parte de esquema está: la tabla `Cliente` existe desde V2 (nombre, whatsapp, notas) y `venta.cliente_id` es una FK nullable que la referencia. Lo que no existe es **nada más**: ni CRUD, ni endpoints, ni pantalla, ni forma de asociar una venta a una clienta. La Fase 8 dejó `clienteId` fuera de `PeticionesVentas` a propósito, porque no hay de dónde sacar el id. Quedó colgada de la peor manera posible: se respondió que sí se guardan clientes, se pagó el costo de esquema para no reconstruir después, y ahí se detuvo. Decidir explícitamente una de dos — se construye el flujo (¿para qué: fiado, apartados, recordar preferencias de tono, mandar el recibo por WhatsApp?) o se declara que la tabla queda dormida — porque una tabla vacía que nadie llena es indistinguible de un olvido, y dentro de un año nadie va a recordar cuál de las dos cosas era.
- **Ventas fiadas / crédito.** Muy común en el comercio pequeño colombiano. Implica tablas de saldo y abonos, y cambia la lógica de caja: una venta fiada **no** genera movimiento de efectivo.
- **Apartados / separados.** También muy común. Requiere el concepto de *stock reservado* vs *stock disponible*, que toca el cálculo central del inventario.
- **Devoluciones y cambios.** Está el tipo de movimiento, pero no el flujo ni las tablas. En maquillaje el cambio de tono es frecuente.
- **Anulación de venta.** Distinta de una devolución: es corregir un cobro mal hecho en el momento.
- **Descuentos — resuelto en el esquema y en el backend, sin pantalla todavía. Es el mismo agujero que el de clientes, así que queda anotado igual para que no se pierda por el mismo camino.** Lo decidido: el descuento es un **monto en pesos sobre el total de la venta**, no un porcentaje ni un valor por línea; `PeticionesVentas.Venta.descuento` lo acepta, `ServicioVenta` lo valida contra el subtotal, `Prorrateo.repartir()` lo reparte proporcionalmente entre las líneas y lo congela en `venta_item.descuento_prorrateado`, con dos tests que sostienen las igualdades (`DescuentoProrrateadoTest` y `VentaHttpTest`). Lo que falta es **el campo en la pantalla de cobro**: la Fase 9 lo dejó fuera a propósito para no ampliar el alcance, y mientras no esté, la funcionalidad es inalcanzable — existe entera y nadie puede usarla, que es la peor de las dos formas de no tenerla. Decidido también, para cuando se construya: **lo aplica la EMPLEADA sin autorización de nadie** (negociar el precio en el mostrador es parte de vender, y una tienda de maquillaje hace promociones), queda **grabado con el usuario que lo aplicó** —`venta.usuario_id` ya lo guarda— y **aparece en métricas por persona**, que es el control que de verdad sirve: no impedir el descuento, sino poder ver quién descuenta y cuánto.
- ~~**Usuarios.**~~ **Resuelto en V2 y V3:** `Usuario` con rol y PIN BCrypt, `IntentoLogin` para el bloqueo, y FK desde `SesionCaja`, `Venta` y `MovimientoInventario`. La separación de permisos dueña/empleada está implementada en `PermisosPorRol`.
- **Imágenes de producto.** El maquillaje es visual y los tonos se distinguen mal por nombre. Define carpeta y columna, o descártalo explícitamente.

### 11.2 Se pueden agregar después

- ~~**Búsqueda en el POS.**~~ **Hecho.** `BuscadorDeVariante` filtra en memoria sobre el catálogo ya cargado, cruzando marca, producto, tono y tamaño a la vez, y lo usan tanto la captura de compras como el cobro. Se sirve de `catalogo.filas` —solo variantes con historial—, porque no se puede vender lo que nunca entró.
- **Métricas concretas.** Está el módulo pero no la lista: ventas por día/semana/mes, margen bruto, productos más vendidos, productos sin rotación, valor total del inventario a costo, alertas de stock bajo, ventas por método de pago, comparativo entre períodos.
- **Exportar a Excel** para el contador.
- **Enviar recibo por WhatsApp** (adjuntar el PDF).
- **Conteo físico / inventario cíclico** — pantalla para contar y generar ajustes en lote.
- **Alertas de vencimiento y PAO** — los campos existen, falta la lógica.
- **Gastos operativos** más allá de los que salen de caja.

---

## Resumen de prioridad de construcción

1. Esquema Flyway V1 completo (13 tablas + lo que salga de la sección 11.1)
2. Respaldo automático
3. Catálogo
4. Compras y movimientos de inventario
5. Caja
6. Punto de venta
7. Recibos PDF
8. Métricas
