#!/usr/bin/env node
/**
 * Comprueba que las pruebas de comportamiento son portantes.
 *
 * El mismo criterio que en el backend: una prueba en verde no demuestra que la
 * regla este protegida. Puede estar afirmando algo que se cumple por casualidad,
 * o mirando un elemento que existiria igual sin la regla. La unica demostracion
 * es romper el codigo a proposito y ver caer exactamente la prueba que cubre esa
 * regla.
 *
 * Cada caso muta un archivo de src/, corre solo el archivo de pruebas que le
 * toca, y exige que la prueba nombrada falle. Restaura en `finally`.
 *
 * npm run pruebas:romper
 */
import { spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const RAIZ = fileURLToPath(new URL('..', import.meta.url))
const src = (...partes) => join(RAIZ, 'src', ...partes)

const LOGIN = src('pantallas', 'Login.jsx')
const CATALOGO = src('pantallas', 'Catalogo.jsx')
const FORMULARIO = src('pantallas', 'FormularioProducto.jsx')
const USE_CATALOGO = src('catalogo', 'useCatalogo.js')
const BOTON = src('componentes', 'Boton.jsx')
const ARMAZON = src('pantallas', 'Armazon.jsx')
const ENDPOINTS = src('api', 'endpoints.js')
const REGISTRAR_COMPRA = src('pantallas', 'RegistrarCompra.jsx')
const BAJAS = src('pantallas', 'BajasDeCompra.jsx')
const CAJA = src('pantallas', 'Caja.jsx')
const VENTA = src('pantallas', 'Venta.jsx')
const LISTADO_VENTAS = src('pantallas', 'Ventas.jsx')
const CERRAR_CAJA = src('pantallas', 'CerrarCaja.jsx')
const CONTADOR = src('componentes', 'ContadorDeDenominaciones.jsx')
const MODAL = src('componentes', 'Modal.jsx')
const MARCAS = src('pantallas', 'MarcasYCategorias.jsx')
const BUSCADOR = src('componentes', 'BuscadorDeVariante.jsx')
const METRICAS = src('pantallas', 'Metricas.jsx')

function sustituir(de, a) {
  return (contenido) => {
    if (!contenido.includes(de)) {
      throw new Error(`el caso esperaba encontrar ${JSON.stringify(de.slice(0, 60))} y no está`)
    }
    return contenido.replace(de, a)
  }
}

const CASOS = [
  {
    regla: 'al fallar, el PIN se limpia para volver a intentar de una',
    archivo: LOGIN,
    pruebas: 'pruebas/Login.prueba.jsx',
    debeCaer: 'al fallar limpia el PIN',
    romper: sustituir("      fijarPin('')\n      if (fallo.codigo", '      if (fallo.codigo'),
  },
  {
    regla: 'el login se envía una sola vez, incluso con StrictMode doblando los updaters',
    archivo: LOGIN,
    pruebas: 'pruebas/Login.prueba.jsx',
    debeCaer: 'envía el login UNA sola vez',
    romper: sustituir(
      `    const siguiente = pinActual.current + digito
    fijarPin(siguiente)
    if (siguiente.length === LARGO_PIN) intentar(siguiente)`,
      `    setPin((actual) => {
      const siguiente = actual + digito
      if (siguiente.length === LARGO_PIN) intentar(siguiente)
      return siguiente
    })`,
    ),
  },
  {
    regla: 'el bloqueo se anuncia con su cuenta regresiva, no en silencio',
    archivo: LOGIN,
    pruebas: 'pruebas/Login.prueba.jsx',
    debeCaer: 'cuando el bloqueo se activa lo dice',
    romper: sustituir(
      'setSegundosDeBloqueo(Number(fallo.datos.reintentarEnSegundos) || 0)',
      'setSegundosDeBloqueo(0)',
    ),
  },
  {
    regla: 'stock bajo es stock < stockMinimo, el mismo criterio que bajoMinimo() del backend',
    archivo: USE_CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'el filtro de stock bajo usa el mismo criterio',
    romper: sustituir(
      'stockBajo: variante.stock < variante.stockMinimo',
      'stockBajo: variante.stock <= variante.stockMinimo',
    ),
  },
  {
    regla: 'la búsqueda filtra en memoria, sin una llamada por tecla',
    archivo: CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'la búsqueda filtra sin llamar al servidor',
    romper: sustituir(
      'onChange={(e) => setTexto(e.target.value)}',
      "onChange={(e) => { setTexto(e.target.value); fetch('/api/v1/catalogo') }}",
    ),
  },
  {
    regla: 'la EMPLEADA no pide costos a ningún endpoint',
    archivo: CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'no pide costos a ningún endpoint',
    romper: sustituir(
      'onChange={(e) => setTexto(e.target.value)}',
      "onChange={(e) => { setTexto(e.target.value); fetch('/api/v1/catalogo/costos') }}",
    ),
  },
  {
    regla: 'el botón se bloquea mientras hay una petición en vuelo',
    archivo: BOTON,
    pruebas: 'pruebas/CargaInicial.prueba.jsx',
    debeCaer: 'el botón queda bloqueado mientras hay un envío en vuelo',
    romper: sustituir('disabled={disabled || ocupado}', 'disabled={disabled}'),
  },
  {
    regla: 'NOMBRE_DUPLICADO se muestra bajo el nombre, ramificando sobre el código',
    archivo: FORMULARIO,
    pruebas: 'pruebas/FormularioProducto.prueba.jsx',
    debeCaer: 'NOMBRE_DUPLICADO se muestra debajo del nombre',
    romper: sustituir(
      "const errorDeNombre = error?.codigo === CODIGOS.nombreDuplicado",
      "const errorDeNombre = false && error?.codigo === CODIGOS.nombreDuplicado",
    ),
  },

  // ------------------------------------------------------------ Fase 6

  {
    regla: 'el total que queda en pantalla es el que devolvió el servidor',
    archivo: REGISTRAR_COMPRA,
    pruebas: 'pruebas/Compras.prueba.jsx',
    debeCaer: 'al guardar muestra el total que devolvió el servidor',
    // La regresion realista: quedarse con el total que la pantalla venia sumando
    // en vez del que quedo guardado. Con un fixture "coherente" no se notaria.
    romper: sustituir('setGuardada(respuesta)',
      'setGuardada({ ...respuesta, total: totalOrientativo })'),
  },
  {
    regla: 'la confirmación de la recepción no manda ningún número de la previa',
    archivo: ENDPOINTS,
    pruebas: 'pruebas/RecibirCompra.prueba.jsx',
    debeCaer: 'al confirmar manda solo el id',
    romper: sustituir(
      "recibir: (id) => api.post(`/api/v1/compras/${id}/recepcion`),",
      "recibir: (id) => api.post(`/api/v1/compras/${id}/recepcion`, { total: 1 }),",
    ),
  },
  {
    regla: 'la EMPLEADA no tiene Compras en el DOM, no solo deshabilitada',
    archivo: ARMAZON,
    pruebas: 'pruebas/Navegacion.prueba.jsx',
    debeCaer: 'la EMPLEADA no tiene Compras ni Métricas en el DOM',
    romper: sustituir(
      'const pestanas = seccion.pestanas.filter((p) => !p.permiso || puede(p.permiso))',
      'const pestanas = seccion.pestanas',
    ),
  },
  {
    regla: 'anular nombra las variantes que quedarían en stock negativo',
    archivo: BAJAS,
    pruebas: 'pruebas/RecibirCompra.prueba.jsx',
    debeCaer: 'avisa qué variantes quedan en negativo',
    romper: sustituir(
      'const negativas = previa?.lineas.filter((linea) => linea.quedaNegativo) ?? []',
      'const negativas = []',
    ),
  },
  {
    regla: 'el stock negativo se distingue del stock bajo en el catálogo',
    archivo: USE_CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'el stock negativo se marca distinto del stock bajo',
    romper: sustituir('stockNegativo: variante.stock < 0', 'stockNegativo: false'),
  },

  // ------------------------------------------------------------ Fase 7

  {
    regla: 'la API descarta el monto de los movimientos antes de que la pantalla lo vea',
    archivo: ENDPOINTS,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'la API entrega los movimientos sin su monto',
    romper: sustituir('const sinMonto = ({ monto, ...resto }) => resto',
      'const sinMonto = (movimiento) => movimiento'),
  },
  {
    regla: 'un monto que llegue a la pantalla por cualquier vía se ve en el DOM',
    archivo: ENDPOINTS,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'ningún importe de los movimientos registrados queda en la pantalla',
    // El descarte se rompe de la forma que de verdad pasaria: el monto vuelve, y de
    // paso entra en un campo que la pantalla si pinta. Sin las dos cosas la prueba del
    // DOM no podria caer, porque la lista no tiene columna de monto — que es
    // justamente la segunda capa de la defensa.
    romper: sustituir('const sinMonto = ({ monto, ...resto }) => resto',
      'const sinMonto = (m) => ({ ...m, concepto: `${m.concepto} ${Math.abs(m.monto)}` })'),
  },
  {
    regla: 'el historial no publica la base siguiente, que es la base inicial de hoy',
    archivo: CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'no muestra la base siguiente de una sesión cerrada',
    romper: sustituir('<td>{sesion.consecutivo}</td>',
      '<td>{sesion.consecutivo} {sesion.baseSiguiente}</td>'),
  },
  {
    regla: 'la sesión olvidada de un día anterior bloquea todo lo demás',
    archivo: CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'bloquea todo lo demás y dice de qué fecha es',
    romper: sustituir('if (sesion?.esDeUnDiaAnterior) {', 'if (false) {'),
  },
  {
    regla: 'el historial en línea se corta en diez sesiones',
    archivo: CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'se corta en diez sesiones y ofrece ver todas',
    romper: sustituir('const SESIONES_EN_LINEA = 10', 'const SESIONES_EN_LINEA = 100'),
  },
  {
    regla: 'el historial se pide al backend, no se filtra por usuario en el front',
    archivo: CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'pinta las sesiones de otra persona sin filtrarlas',
    romper: sustituir("const cerradas = sesiones.filter((una) => una.estado === 'CERRADA')",
      "const cerradas = sesiones.filter((una) => una.estado === 'CERRADA'"
        + " && una.usuarioApertura === 'Camila')"),
  },
  {
    regla: 'la confirmación del conteo es un paso propio: contar y cerrar no son el mismo clic',
    archivo: CERRAR_CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'no muestra nada del sistema hasta que el conteo está enviado',
    romper: sustituir("onClick={() => setPaso('confirmar')}", 'onClick={cerrar}'),
  },
  {
    // Lo mismo que protege un doble guardado en la carga inicial protege aqui un
    // cierre doble, que es irreversible: vale la pena afirmarlo tambien sobre la caja.
    regla: 'el botón bloqueado es lo único que impide cerrar la caja dos veces',
    archivo: BOTON,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'el cierre se envía una sola vez aunque se pulse dos veces seguidas',
    romper: sustituir('disabled={disabled || ocupado}', 'disabled={disabled}'),
  },
  {
    regla: 'una sesión con diferencia y sin notas se marca como sin explicar',
    archivo: CERRAR_CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'marca "sin explicar" la sesión con diferencia y sin ninguna nota',
    romper: sustituir(
      'return sesion.diferencia !== 0 && (sesion.notas?.length ?? 0) === 0',
      'return false'),
  },
  {
    regla: 'sobrante y faltante se distinguen: no es lo mismo que falte a que sobre',
    archivo: CERRAR_CAJA,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'dice "sobraron" cuando la diferencia es a favor',
    romper: sustituir(
      "if (diferencia > 0) return { texto: 'sobraron', tono: 'favor', cuanto: diferencia }",
      "if (diferencia > 0) return { texto: 'faltaron', tono: 'contra', cuanto: diferencia }"),
  },
  {
    regla: 'una variante sin historial no se lista en el catálogo',
    archivo: USE_CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'una variante sin historial no se lista',
    romper: sustituir(
      'const filas = useMemo(() => todas.filter((fila) => fila.conHistorial), [todas])',
      'const filas = todas'),
  },
  {
    // La misma rotura, otra garantia: `filas` es tambien de donde se serviria el
    // buscador de venta, y con el filtro caido sugiere lo que nunca entro.
    regla: 'con el filtro caído, el buscador sugiere lo que nunca entró',
    archivo: USE_CATALOGO,
    pruebas: 'pruebas/Catalogo.prueba.jsx',
    debeCaer: 'tampoco la sugiere el buscador',
    romper: sustituir(
      'const filas = useMemo(() => todas.filter((fila) => fila.conHistorial), [todas])',
      'const filas = todas'),
  },
  {
    regla: 'la compra sí puede elegir una variante sin historial: la mercancía está llegando',
    archivo: REGISTRAR_COMPRA,
    pruebas: 'pruebas/Compras.prueba.jsx',
    debeCaer: 'el buscador de la compra sí encuentra una variante sin historial',
    romper: sustituir('filas={catalogo.todas}', 'filas={catalogo.filas}'),
  },
  // ------------------------------------------------------------ Fase 9

  {
    regla: 'el uuid se descarta SOLO tras un cobro exitoso, no tras uno fallido',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'tras un fallo de red el reintento manda el mismo uuid',
    // La regresion realista: "limpiar" el carrito por completo al fallar, incluido el
    // uuid. Con eso, la respuesta perdida despues del commit se convierte en venta
    // doble e inventario descontado dos veces.
    romper: sustituir('      huboFallo.current = true\n      setError(fallo)',
      '      huboFallo.current = true\n      uuid.current = nuevoUuid()\n      setError(fallo)'),
  },
  {
    regla: 'el uuid SI se descarta tras el exito: dos ventas seguidas son dos ventas',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'dos ventas seguidas del mismo producto crean dos ventas distintas',
    romper: sustituir('      uuid.current = nuevoUuid()\n      huboFallo.current = false',
      '      huboFallo.current = false'),
  },
  {
    regla: 'un 200 tras un fallo es la recuperacion esperada y no alarma',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'el 200 que recupera un cobro fallido no se anuncia como problema',
    romper: sustituir('uuidReutilizado: estado === 200 && !huboFallo.current,',
      'uuidReutilizado: estado === 200,'),
  },
  {
    regla: 'la variante sin costo se rechaza al agregar al carrito, no al cobrar',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'una variante sin costo no entra al carrito',
    romper: sustituir('    if (fila.sinCosto) {', '    if (false && fila.sinCosto) {'),
  },
  {
    regla: 'sin sesion de caja abierta no se arma el carrito',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'sin caja abierta no deja armar el carrito',
    // Tratar el 404 como "ya hay caja": la forma en que esto se rompe de verdad es
    // dando por buena una respuesta que dice justamente lo contrario.
    romper: sustituir('        if (fallo.codigo === CODIGOS.noEncontrado) return null',
      '        if (fallo.codigo === CODIGOS.noEncontrado) '
        + "return { consecutivo: 'C-1', esDeUnDiaAnterior: false }"),
  },
  {
    regla: 'la caja olvidada de un dia anterior tampoco deja vender',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'con la caja de un día anterior tampoco deja vender',
    romper: sustituir('  if (!sesion || sesion.esDeUnDiaAnterior) {', '  if (!sesion) {'),
  },
  {
    regla: 'el cambio que queda en pantalla es el del servidor, no el que se calculo',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'el cambio que queda en pantalla es el del servidor',
    romper: sustituir(
      '<span className="monto venta__cifra-media">{formatearPesos(venta.cambio)}</span>',
      '<span className="monto venta__cifra-media">'
        + '{formatearPesos(cambioQueMostroLaPantalla)}</span>'),
  },
  {
    regla: 'el stock negativo se avisa sin presentarlo como un error de la venta',
    archivo: VENTA,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'avisa del stock negativo sin presentarlo como un error',
    romper: sustituir('<Aviso tipo="alerta" titulo="Quedó stock en negativo">',
      '<Aviso tipo="error" titulo="Quedó stock en negativo">'),
  },
  {
    regla: 'el cobro descuenta el stock del catalogo en memoria',
    archivo: USE_CATALOGO,
    pruebas: 'pruebas/Venta.prueba.jsx',
    debeCaer: 'el cobro descuenta del catálogo en memoria',
    romper: sustituir('    if (!lineas?.length) return', '    if (lineas) return'),
  },
  {
    regla: 'anular es solo de la DUENA: a la EMPLEADA no se le ofrece',
    archivo: LISTADO_VENTAS,
    pruebas: 'pruebas/Ventas.prueba.jsx',
    debeCaer: 'la EMPLEADA no ve el botón de anular',
    romper: sustituir('puedeAnular={puede(PERMISOS.anularVentas)}', 'puedeAnular'),
  },
  {
    regla: 'el motivo de la anulacion es obligatorio',
    archivo: LISTADO_VENTAS,
    pruebas: 'pruebas/Ventas.prueba.jsx',
    debeCaer: 'no deja anular sin motivo',
    romper: sustituir('               disabled={!motivo.trim()}>', '>'),
  },

  {
    regla: 'ningún identificador de código llega a la pantalla: el rol se traduce',
    archivo: ARMAZON,
    pruebas: 'pruebas/Etiquetas.prueba.jsx',
    debeCaer: 'el encabezado dice el rol con palabras, no DUENA',
    romper: sustituir('{etiqueta(usuario.rol)}', '{usuario.rol}'),
  },
  {
    regla: 'tampoco en la caja, donde VENTA_EFECTIVO lo escribe el cobro y no una persona',
    archivo: CAJA,
    pruebas: 'pruebas/Etiquetas.prueba.jsx',
    debeCaer: 'los movimientos de caja no muestran VENTA_EFECTIVO',
    romper: sustituir(
      '{POR_ID.get(movimiento.tipo)?.texto ?? etiqueta(movimiento.tipo)}',
      '{POR_ID.get(movimiento.tipo)?.texto ?? movimiento.tipo}'),
  },
  {
    regla: 'el modal respeta el campo con autoFocus en vez de saltar siempre al primero',
    archivo: MODAL,
    pruebas: 'pruebas/Compras.prueba.jsx',
    debeCaer: 'crear producto y variante durante la compra se hace solo con el teclado',
    romper: sustituir(
      'if (!caja.current?.contains(document.activeElement)) {',
      'if (true) {'),
  },
  {
    regla: 'los productos se cuentan por marca, no todos contra todas',
    archivo: MARCAS,
    pruebas: 'pruebas/MarcasYCategorias.prueba.jsx',
    debeCaer: 'cuenta los productos de cada una y marca las que no tienen ninguno',
    romper: sustituir(
      'porMarca.set(producto.marcaId, (porMarca.get(producto.marcaId) ?? 0) + 1)',
      'for (const marca of catalogo.marcas) porMarca.set(marca.id, (porMarca.get(marca.id) ?? 0) + 1)'),
  },
  {
    regla: 'el recibo se abre en el visor del sistema, no en una ventana del navegador',
    archivo: LISTADO_VENTAS,
    pruebas: 'pruebas/Ventas.prueba.jsx',
    debeCaer: 'ver el recibo se lo pide al backend, no abre una ventana del navegador',
    // La regresion realista: "simplificarlo" a abrir la URL del PDF. En modo app eso
    // deja una ventana de navegador suelta encima del mostrador.
    romper: sustituir('? recibo.abrir(venta.id)',
      "? window.open(`/api/v1/ventas/${venta.id}/recibo`)"),
  },
  {
    regla: 'la fila ofrece ver o generar segun tenga o no archivo',
    archivo: LISTADO_VENTAS,
    pruebas: 'pruebas/Ventas.prueba.jsx',
    debeCaer: 'ofrece ver el recibo cuando hay archivo y generarlo cuando no',
    romper: sustituir("{venta.rutaRecibo ? 'Ver recibo' : 'Generar recibo'}", "{'Ver recibo'}"),
  },
  {
    regla: 'la descripcion de la variante es la del backend, no una rearmada en el front',
    archivo: BUSCADOR,
    pruebas: 'pruebas/BuscadorDeVariante.prueba.jsx',
    debeCaer: 'muestra la descripción del backend tal cual, sin rearmarla',
    romper: sustituir("  return fila.descripcion ?? ''",
      "  return [fila.marcaNombre, fila.productoNombre, fila.tono].filter(Boolean).join(' · ')"),
  },
  {
    regla: 'el desplegable es mas ancho que la celda, para que una opcion sea una linea',
    archivo: BUSCADOR,
    pruebas: 'pruebas/BuscadorDeVariante.prueba.jsx',
    debeCaer: 'es al menos tan ancha como su min-width, aunque el campo sea angosto',
    // La regresion realista: "simplificar" el ancho al del campo, que es lo que
    // parece obvio y lo que parte cada opcion en dos lineas.
    romper: sustituir('const ancho = Math.max(campo.width, minimo)',
      'const ancho = campo.width'),
  },
  {
    regla: 'la altura del desplegable sale del espacio libre, no de una cuenta de filas',
    archivo: BUSCADOR,
    pruebas: 'pruebas/BuscadorDeVariante.prueba.jsx',
    debeCaer: 'la altura máxima es el espacio libre, no una cuenta de filas',
    romper: sustituir('        maxHeight: Math.max(haciaArriba ? espacioArriba : espacioAbajo, 0),'
      + String.fromCharCode(10), ''),
  },
  {
    regla: 'el desplegable se ancla en coordenadas de ventana, fuera de lo que lo recorta',
    archivo: BUSCADOR,
    pruebas: 'pruebas/BuscadorDeVariante.prueba.jsx',
    debeCaer: 'se ancla al campo con coordenadas propias, no al contenedor',
    romper: sustituir('style={ancla ?? undefined}', ''),
  },
  {
    regla: 'el total contado suma denominación por cantidad, que es como se cuenta la plata',
    archivo: CONTADOR,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'no muestra nada del sistema hasta que el conteo está enviado',
    romper: sustituir(
      'suma + denominacion * (Number(conteo[denominacion]) || 0)',
      'suma + denominacion + (Number(conteo[denominacion]) || 0)'),
  },
  {
    regla: 'el margen porcentual nulo es "sin datos", no 0 %',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'el margen porcentual nulo se muestra como',
    romper: sustituir(
      "cifra={actual.margenPorcentaje === null ? '—' : `${actual.margenPorcentaje} %`}",
      'cifra={`${actual.margenPorcentaje ?? 0} %`}',
    ),
  },
  {
    regla: 'sin ventas en el periodo anterior no se muestra variación',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'si el periodo anterior no tuvo ventas',
    romper: sustituir('const comparable = anterior.ventas > 0', 'const comparable = true'),
  },
  {
    regla: 'el detalle de vencimientos se pide una vez y solo al abrirlo',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'el detalle se pide al pulsar',
    romper: sustituir('    if (detalle || cargando) return\n', ''),
  },
  {
    regla: 'un cero en vencidos no se pinta de error',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'un cero en vencidos no se pinta de error',
    romper: sustituir("tono={conteos.vencidos > 0 ? 'error' : undefined}", "tono=\"error\""),
  },
  {
    regla: 'el stock negativo se distingue del stock bajo',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'el inventario muestra valor, unidades',
    romper: sustituir('{fila.stock < 0\n', '{false\n'),
  },
  {
    regla: 'cada ranking respeta el orden que manda el backend',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'muestra dos rankings y respeta el orden',
    romper: sustituir('{filas.map((fila, indice) => (\n                <tr key={fila.varianteId}>\n                  <td>{indice + 1}</td>',
      '{[...filas].sort((a, b) => b.unidades - a.unidades).map((fila, indice) => (\n                <tr key={fila.varianteId}>\n                  <td>{indice + 1}</td>'),
  },
  {
    regla: 'el mes navega desde el día 1: un 31 de marzo atrás es febrero, no el 3 de marzo',
    archivo: METRICAS,
    pruebas: 'pruebas/Metricas.prueba.jsx',
    debeCaer: 'desde 2026-03-31 va a 2026-02-01',
    romper: sustituir('fecha.getMonth() + sentido, 1))', 'fecha.getMonth() + sentido, fecha.getDate()))'),
  },
  {
    regla: 'las métricas son de la DUENA: la EMPLEADA no las tiene ni en el DOM',
    archivo: ARMAZON,
    pruebas: 'pruebas/Navegacion.prueba.jsx',
    debeCaer: 'la EMPLEADA no tiene Compras ni Métricas en el DOM',
    romper: sustituir('    permiso: PERMISOS.verMetricas,\n', ''),
  },
]

// El binario de vitest directamente, no `npx` con shell: pasar argumentos por el
// shell es una via de inyeccion y aqui no aporta nada.
const VITEST = join(RAIZ, 'node_modules', 'vitest', 'vitest.mjs')

function correrPruebas(archivo) {
  const resultado = spawnSync(process.execPath, [VITEST, 'run', archivo], {
    cwd: RAIZ,
    encoding: 'utf8',
  })
  return {
    codigo: resultado.status,
    salida: `${resultado.stdout ?? ''}${resultado.stderr ?? ''}`,
  }
}

console.log('Comprobando que las pruebas de comportamiento son portantes\n')

let fallidos = 0

for (const caso of CASOS) {
  const original = readFileSync(caso.archivo, 'utf8')
  let veredicto
  try {
    writeFileSync(caso.archivo, caso.romper(original))
    const { codigo, salida } = correrPruebas(caso.pruebas)

    if (codigo === 0) {
      veredicto = { ok: false, motivo: 'la tanda pasó igual: la prueba no cubre esa regla' }
    } else if (!salida.includes(caso.debeCaer)) {
      veredicto = {
        ok: false,
        motivo: `algo falló, pero no "${caso.debeCaer}": cayó otra prueba, así que esta `
          + 'no está protegiendo lo que dice',
      }
    } else {
      veredicto = { ok: true }
    }
  } catch (fallo) {
    veredicto = { ok: false, motivo: fallo.message }
  } finally {
    writeFileSync(caso.archivo, original)
  }

  if (veredicto.ok) {
    console.log(`  cae    ${caso.regla}`)
  } else {
    fallidos += 1
    console.log(`  NO CAE ${caso.regla}`)
    console.log(`         ${veredicto.motivo}`)
  }
}

console.log('')
if (fallidos > 0) {
  console.error(`${fallidos} regla(s) sin prueba que las sostenga.`)
  process.exit(1)
}
console.log(`Las ${CASOS.length} roturas hacen caer la prueba que les corresponde.`)
