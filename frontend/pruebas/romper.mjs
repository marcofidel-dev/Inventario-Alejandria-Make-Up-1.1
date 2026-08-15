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
const CERRAR_CAJA = src('pantallas', 'CerrarCaja.jsx')
const CONTADOR = src('componentes', 'ContadorDeDenominaciones.jsx')

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
  {
    regla: 'el total contado suma denominación por cantidad, que es como se cuenta la plata',
    archivo: CONTADOR,
    pruebas: 'pruebas/Caja.prueba.jsx',
    debeCaer: 'no muestra nada del sistema hasta que el conteo está enviado',
    romper: sustituir(
      'suma + denominacion * (Number(conteo[denominacion]) || 0)',
      'suma + denominacion + (Number(conteo[denominacion]) || 0)'),
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
