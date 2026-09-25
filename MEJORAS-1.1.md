# Mejoras 1.1 — hallazgos de prueba

Organizados por lo que bloquea a lo que puede esperar. La carga del
inventario real es lo próximo que va a pasar, así que lo que la estorba
va primero.

---

## A. Bloquea la carga del catálogo real

Tu novia va a pasar horas en estas pantallas metiendo cientos de
productos. Un defecto aquí se paga en trabajo manual que toca rehacer.

### A1. El modal de compras es muy pequeño

Al registrar un producto o su variante durante una compra, la ventana
ocupa una fracción de la pantalla y obliga a desplazarse con el mouse.
Es el formulario donde más tiempo se va a pasar.

- Que use el ancho disponible en pantallas grandes.
- Sin scroll interno para los campos normales.
- El foco encadenado tiene que funcionar sin tocar el mouse en ningún
  momento: es captura masiva desde una factura.

### A2. Falta la pantalla de marcas y categorías

Quedó declarada como "próximamente" desde la Fase 6.

Después del cambio de alcance, marcas y categorías se **crean** desde el
desplegable durante la compra. Esta pantalla es para **administrarlas**:
renombrar, desactivar, ver cuáles no tiene ningún producto.

**Decisión pendiente:** ¿hace falta *fusionar* duplicadas? El índice
normalizado atrapa "Loréal" contra "LOREAL", pero no "Loreal" contra
"L'Oréal Paris". Con dos personas escribiendo, van a aparecer. Fusionar
es reasignar los productos de una marca a otra y desactivar la vacía —
es trabajo real, y solo vale la pena si el caso se da.

### A3. Fusionar Inventario y Catálogo

Hoy son dos secciones que parecen hacer lo mismo, y en parte lo hacen.
El origen: al quitar la creación del catálogo, esa capacidad se mudó a
Inventario › Carga inicial. Desde afuera se ve como dos puertas al mismo
sitio.

Además "Existencias" iba a mostrar una lista con stock, que es
exactamente lo que ya muestra "Productos".

**Queda una sola sección**, con el nombre que usa la dueña:

```
Inventario   [ Productos | Ajustes | Carga inicial | Marcas y categorías ]
```

- Desaparece "Catálogo" como sección.
- Desaparece "Existencias": era un duplicado de Productos.
- "Productos" sigue siendo la vista principal: buscar, ver stock, editar
  precio y stock mínimo, activar y desactivar. No crea.
- Carga inicial queda de última: se usa unos días al principio de la vida
  del sistema y después nunca.

---

## B. Diagnosticar primero

### B1. El recibo de venta

Síntoma reportado: falta, no se implementó bien, o no se ejecuta.

El generador atrapa cualquier fallo y lo registra sin tumbar la venta, así
que un error de generación es invisible desde la pantalla. Hay que
distinguir tres casos antes de arreglar nada:

```
ls -R %APPDATA%\AlejandriaMakeUp\dev\recibos\
```

| Qué se ve | Qué significa | Arreglo |
|---|---|---|
| Hay PDFs | Se genera bien, no hay cómo abrirlo desde el cobro | Botón "Ver recibo" tras cobrar |
| Carpeta vacía | El generador falla en silencio | Buscar el error en el log |
| PDFs pero ilegibles | Problema de formato | Revisar `ReciboTexto` |

Y revisar en la base si `venta.ruta_recibo` está lleno o en nulo: eso
separa "no se generó" de "se generó y no se encuentra".

---

## C. Correcciones rápidas

### C1. "DUENA" debe decir "Dueña"

El enum se llama `DUENA` porque es un identificador de código, y el CHECK
de la base también. **No se tocan** — cambiarlos exige migración.

Lo que falta es la etiqueta de pantalla. Y no es solo ese caso: cualquier
enum que llegue a la interfaz necesita su nombre legible.

```
DUENA           -> Dueña
EMPLEADA        -> Empleada
VENTA_EFECTIVO  -> Venta en efectivo
CARGA_INICIAL   -> Carga inicial
BORRADOR        -> Borrador
RECIBIDA        -> Recibida
DESCARTADA      -> Descartada
ANULADA         -> Anulada
COMPLETADA      -> Completada
DAVIPLATA       -> Daviplata
TRANSFERENCIA   -> Transferencia
```

Regla: ningún identificador de código se muestra en pantalla. Un guion
bajo visible es un bug.

### C2. Icono e identidad

- Icono de la aplicación (`.ico`) para `jpackage`.
- Favicon: como la app abre en modo aplicación, el favicon es lo que se
  ve en la barra de la ventana.
- Nombre y logo dentro de la app, en el encabezado.

---

## D. Fases propias

### D1. Corregir una compra ya recibida

El cliente lo pide y se puede dar exactamente eso. Lo que no se puede es
un `UPDATE`: el ledger es append-only y una compra recibida ya movió el
inventario y el costo promedio.

**El botón dice "Corregir"** y por debajo hace lo que ya está diseñado:
anula la compra, crea un borrador nuevo precargado con las mismas líneas,
y lo abre para editar y volver a recibir.

Primero confirmar si ya se construyó. Si existe pero el botón dice
"Anular", el problema es de nombre, no de funcionalidad.

**Advertencia obligatoria:** entre anular y recibir el borrador
corregido, el stock queda descuadrado —la mercancía está en la tienda
pero el sistema ya la descontó.

### D2. Pantalla de métricas

Backend terminado, interfaz nunca construida. Es la única fase pendiente
del alcance original.

Conviene hacerla cuando ya haya ventas reales: sin datos no hay qué
mostrar, y se probaría con un seed en vez de con cifras de verdad.

### D3. Diseño responsive

Verificado: el acceso desde el celular por la wifi **funciona**, la
conexión es estable y el inventario carga completo. Lo que falla es solo
la usabilidad a ~380px.

Alcance: consultar catálogo y stock, sí. Cobrar, comprar y cerrar caja,
no — son tareas de mostrador.

La tabla es el problema real, no el tamaño de los botones: seis columnas
no caben en 380px. En pantalla angosta va un componente distinto —
tarjetas apiladas con producto, precio y stock— no una versión
comprimida de la tabla.

### D4. Distribución visual

Revisión general de espacios y jerarquía. Se hace al final, cuando todas
las pantallas existan y se pueda ver el conjunto.

---

## Decisiones pendientes

| Tema | Pregunta |
|---|---|
| Monedas en el cierre de caja | ¿Trabaja con monedas de $50, $100, $200? Si no las ve nunca, quitarlas acorta el conteo cada noche. |
| Fusionar marcas duplicadas | ¿Hace falta, o basta con renombrar y desactivar? |
| Descuentos en el POS | El backend los soporta y no hay pantalla. Sigue pendiente desde la Fase 9. |
| Clientes | Tabla creada en V2, nunca construida. Se decidió guardar nombre y WhatsApp y quedó sin hacer. |
