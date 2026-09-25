#!/usr/bin/env node
/**
 * Guardas del sistema de diseno.
 *
 * Todas protegen fallas que NO SE NOTAN MIRANDO LA PANTALLA. Un var() mal escrito
 * no da error: la propiedad simplemente no se aplica. Una fuente que no cargo
 * tampoco: el navegador sustituye en silencio. Un rosa con texto blanco encima se
 * "ve" — apenas — hasta que alguien tiene que leerlo con luz de tienda.
 *
 * Sin dependencias: se ejecuta con el node que ya empaqueta el proyecto.
 *
 * npm run guardas
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// fileURLToPath y no URL.pathname: la ruta del proyecto tiene un espacio, y
// pathname lo entrega como %20.
const RAIZ = fileURLToPath(new URL('..', import.meta.url))
const FUENTE = join(RAIZ, 'src')
const TOKENS = join(FUENTE, 'estilos', 'tokens.css')
const FUENTES = join(FUENTE, 'estilos', 'fuentes.js')

const MAXIMO_MOVIMIENTO_MS = 240
const CONTRASTE_MINIMO = 4.5

const fallos = []
const avisos = []

function fallar(guarda, detalle) {
  fallos.push({ guarda, detalle })
}

// ---------------------------------------------------------------- utilidades

function archivos(directorio, extensiones) {
  const encontrados = []
  for (const entrada of readdirSync(directorio)) {
    const ruta = join(directorio, entrada)
    if (statSync(ruta).isDirectory()) encontrados.push(...archivos(ruta, extensiones))
    else if (extensiones.some((extension) => entrada.endsWith(extension))) encontrados.push(ruta)
  }
  return encontrados
}

const corto = (ruta) => relative(RAIZ, ruta).replace(/\\/g, '/')

/** Quita comentarios para no acusar a los ejemplos escritos en la documentacion. */
function sinComentarios(texto) {
  return texto.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

const textoDe = (ruta) => readFileSync(ruta, 'utf8')

// ------------------------------------------------------- contraste (WCAG 2.1)

function aCanales(hex) {
  const limpio = hex.replace('#', '')
  const completo = limpio.length === 3 ? [...limpio].map((c) => c + c).join('') : limpio
  return [0, 2, 4].map((i) => parseInt(completo.slice(i, i + 2), 16) / 255)
}

function luminancia(hex) {
  const [r, g, b] = aCanales(hex).map((canal) => (
    canal <= 0.03928 ? canal / 12.92 : ((canal + 0.055) / 1.055) ** 2.4
  ))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contraste(unHex, otroHex) {
  const a = luminancia(unHex)
  const b = luminancia(otroHex)
  const claro = Math.max(a, b)
  const oscuro = Math.min(a, b)
  return (claro + 0.05) / (oscuro + 0.05)
}

// ------------------------------------------------------------------- tokens

const cssTokens = textoDe(TOKENS)

const tokens = new Map()
for (const [, nombre, valor] of cssTokens.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
  if (!tokens.has(nombre)) tokens.set(nombre, valor.trim())
}

/** Resuelve un token hasta su valor literal, siguiendo los var() anidados. */
function resolver(nombre, vistos = new Set()) {
  if (vistos.has(nombre)) return null
  vistos.add(nombre)
  const valor = tokens.get(nombre)
  if (!valor) return null
  const anidado = /^var\((--[a-z0-9-]+)\)$/i.exec(valor)
  return anidado ? resolver(anidado[1], vistos) : valor
}

const color = (nombre) => {
  const valor = resolver(nombre)
  return valor && /^#[0-9a-f]{3,8}$/i.test(valor) ? valor : null
}

// =====================================================================
// 1. Pares de contraste que el sistema declara
// =====================================================================

const ROSAS = ['--marca-rosa-fuerte', '--marca-rosa', '--marca-salmon', '--marca-crema']

const PARES = [
  ['--texto', '--fondo'],
  ['--texto', '--fondo-alterno'],
  ['--texto', '--fondo-hundido'],
  ['--texto-secundario', '--fondo'],
  ['--accion-texto', '--accion'],
  ['--accion-texto', '--accion-hover'],
  ['--accion-texto', '--accion-activo'],
  ['--accion', '--accion-suave'],
  ['--error', '--fondo'],
  ['--error', '--error-fondo'],
  ['--exito', '--fondo'],
  ['--exito', '--exito-fondo'],
  ['--alerta', '--fondo'],
  ['--alerta', '--alerta-fondo'],
  ['--info', '--fondo'],
  ['--info', '--info-fondo'],
  // La regla central: sobre cada uno de los cuatro rosas va texto oscuro.
  ...ROSAS.map((rosa) => ['--texto-sobre-marca', rosa]),
]

for (const [frente, fondo] of PARES) {
  const unColor = color(frente)
  const otroColor = color(fondo)
  if (!unColor || !otroColor) {
    fallar('contraste', `No pude resolver a color ${frente} sobre ${fondo}`)
    continue
  }
  const razon = contraste(unColor, otroColor)
  if (razon < CONTRASTE_MINIMO) {
    fallar('contraste', `${frente} sobre ${fondo} da ${razon.toFixed(2)}, `
      + `por debajo de ${CONTRASTE_MINIMO}`)
  }
}

// =====================================================================
// 2. Tripwire invertido: el supuesto de "nunca texto blanco sobre marca"
//
// Si algun rosa llegara a superar 4.5 contra blanco, la regla del sistema
// dejaria de estar justificada y habria que revisarla. La guarda avisa
// cuando cambia el supuesto, no solo cuando se rompe la regla.
// =====================================================================

const BLANCO = color('--accion-texto') ?? '#FFFFFF'

for (const rosa of ROSAS) {
  const rosaColor = color(rosa)
  if (!rosaColor) {
    fallar('supuesto-marca', `No pude resolver ${rosa} a color`)
    continue
  }
  const razon = contraste(BLANCO, rosaColor)
  if (razon >= CONTRASTE_MINIMO) {
    fallar('supuesto-marca',
      `${rosa} da ${razon.toFixed(2)} contra blanco, o sea que YA aguanta texto claro. `
      + 'El sistema prohibe texto blanco sobre los rosas justamente porque no lo aguantaban: '
      + 'hay que revisar esa regla y los comentarios de tokens.css antes de seguir.')
  }
}

// =====================================================================
// 3. Ninguna duracion por encima del techo
// =====================================================================

for (const [nombre, valor] of tokens) {
  for (const [, cantidad, unidad] of valor.matchAll(/(\d+(?:\.\d+)?)(ms|s)\b/g)) {
    const ms = unidad === 's' ? Number(cantidad) * 1000 : Number(cantidad)
    if (ms > MAXIMO_MOVIMIENTO_MS) {
      fallar('movimiento', `${nombre} vale ${valor}, y el techo del sistema es `
        + `${MAXIMO_MOVIMIENTO_MS}ms`)
    }
  }
}

// =====================================================================
// 4. Ningun literal en componentes y pantallas
// =====================================================================

const NOMBRES_DE_COLOR = /\b(?:red|blue|green|black|white|gray|grey|pink|orange|purple|yellow|crimson|seagreen)\b/i

const cssDeComponentes = archivos(FUENTE, ['.css']).filter((ruta) => ruta !== TOKENS)
const codigo = archivos(FUENTE, ['.jsx', '.js']).filter((ruta) => ruta !== FUENTES)

for (const ruta of [...cssDeComponentes, ...codigo]) {
  const contenido = sinComentarios(textoDe(ruta))

  contenido.split('\n').forEach((linea, indice) => {
    const ubicacion = `${corto(ruta)}:${indice + 1}`

    if (/#[0-9a-f]{3,8}\b/i.test(linea)) {
      fallar('literales', `${ubicacion} escribe un color hexadecimal: ${linea.trim()}`)
    }
    if (/\b(?:rgba?|hsla?)\s*\(/i.test(linea)) {
      fallar('literales', `${ubicacion} escribe un color con función: ${linea.trim()}`)
    }
    if (NOMBRES_DE_COLOR.test(linea) && /(?:color|background|border|fill|stroke)\s*:/i.test(linea)) {
      fallar('literales', `${ubicacion} escribe un color con nombre: ${linea.trim()}`)
    }
    // Longitudes: px, rem y em con valor distinto de cero. Los porcentajes y las
    // fracciones de grid son disposicion, no tipografia ni radio.
    for (const [, cantidad] of linea.matchAll(/(?<![\w-])(\d+(?:\.\d+)?)(?:px|rem|em)\b/g)) {
      if (Number(cantidad) !== 0) {
        fallar('literales', `${ubicacion} escribe una longitud literal: ${linea.trim()}`)
      }
    }
    for (const [, cantidad, unidad] of linea.matchAll(/(?<![\w-])(\d+(?:\.\d+)?)(ms|s)\b(?!\w)/g)) {
      const ms = unidad === 's' ? Number(cantidad) * 1000 : Number(cantidad)
      fallar('literales', `${ubicacion} escribe una duración literal de ${ms}ms: ${linea.trim()}`)
    }
  })
}

// =====================================================================
// 5. Toda var(--…) usada existe en tokens.css
// =====================================================================

const usados = new Set()

for (const ruta of [...cssDeComponentes, ...codigo]) {
  const contenido = sinComentarios(textoDe(ruta))
  for (const [, nombre] of contenido.matchAll(/var\((--[a-z0-9-]+)/gi)) {
    usados.add(nombre)
    if (!tokens.has(nombre)) {
      fallar('variables', `${corto(ruta)} usa ${nombre}, que no está definida en tokens.css. `
        + 'Una variable inexistente no da error: la propiedad no se aplica y el elemento '
        + 'queda sin estilo.')
    }
  }
}

// Al revés, como higiene. No falla el build: un token puede existir para la fase
// siguiente. Pero conviene saberlo.
const definidosSinUsar = [...tokens.keys()].filter((nombre) => {
  if (usados.has(nombre)) return false
  // Los que se usan dentro de tokens.css mismo cuentan como usados.
  const usosInternos = cssTokens.split(`var(${nombre})`).length - 1
  return usosInternos === 0
})

if (definidosSinUsar.length > 0) {
  avisos.push(`Tokens definidos que nadie usa todavía (${definidosSinUsar.length}): `
    + definidosSinUsar.join(', '))
}

// =====================================================================
// 6. Ninguna fuente desde un CDN, ninguna imagen rasterizada
// =====================================================================

for (const ruta of [...cssDeComponentes, ...codigo, TOKENS]) {
  const contenido = sinComentarios(textoDe(ruta))

  if (/url\(\s*['"]?https?:/i.test(contenido) || /fonts\.(?:googleapis|gstatic)\.com/i.test(contenido)) {
    fallar('sin-cdn', `${corto(ruta)} trae un recurso remoto. Las fuentes y los recursos `
      + 'se empaquetan: el día que se caiga el internet, la tienda tiene que seguir abriendo.')
  }
  for (const [, extension] of contenido.matchAll(/\.(png|jpe?g|gif|webp|bmp|ico)\b/gi)) {
    fallar('sin-rasterizadas', `${corto(ruta)} referencia una imagen .${extension}. `
      + 'Los iconos son vectoriales (lucide-react).')
  }
}

// =====================================================================
// 7. Toda familia y peso usados están entre los importados
// =====================================================================

const importesDeFuentes = textoDe(FUENTES)
const pesosImportados = new Map()

for (const [, familia, peso] of importesDeFuentes.matchAll(
  /@fontsource\/([a-z0-9-]+)\/(?:latin(?:-ext)?-)?(\d{3})/gi,
)) {
  if (!pesosImportados.has(familia)) pesosImportados.set(familia, new Set())
  pesosImportados.get(familia).add(peso)
}

if (pesosImportados.size === 0) {
  fallar('fuentes', 'No encontré ningún import de @fontsource en estilos/fuentes.js')
}

// Las familias que declara tokens.css tienen que estar importadas.
for (const [, familiaDeclarada] of cssTokens.matchAll(/--fuente-[a-z]+:\s*'([^']+)'/g)) {
  const identificador = familiaDeclarada.toLowerCase().replace(/\s+/g, '-')
  if (!pesosImportados.has(identificador)) {
    fallar('fuentes', `tokens.css declara la familia "${familiaDeclarada}" pero `
      + 'estilos/fuentes.js no la importa. Una fuente que no cargó no da error: el navegador '
      + 'sustituye en silencio y la pantalla se ve casi bien.')
  }
}

// Y los pesos que declara tokens.css tienen que estar importados para la familia de UI.
const familiaUi = /--fuente-ui:\s*'([^']+)'/.exec(cssTokens)?.[1]?.toLowerCase().replace(/\s+/g, '-')
for (const [, peso] of cssTokens.matchAll(/--peso-[a-z]+:\s*(\d{3});/g)) {
  if (familiaUi && !pesosImportados.get(familiaUi)?.has(peso)) {
    fallar('fuentes', `tokens.css declara el peso ${peso} pero no está importado para `
      + `${familiaUi}.`)
  }
}

// =====================================================================
// 8. Ningun archivo con CRLF
//
// ESTA GUARDA EXISTE PORQUE OTRA GUARDA FALLO EN SILENCIO. `pruebas/romper.mjs` y
// `guardas/romper.mjs` localizan el codigo que van a mutar con cadenas que llevan un
// salto de linea escrito como escape. Un archivo guardado con CRLF —lo hace
// cualquier editor mal configurado, y lo hace Python al escribir en modo texto sobre
// Windows— rompe todo anclaje de dos o mas lineas: el caso reporta "el caso esperaba
// encontrar … y no esta", que se lee como una prueba que dejo de cubrir su regla
// cuando el codigo esta intacto.
//
// Y no se ve: el archivo se abre igual, las pruebas pasan igual, y git diff ni lo
// menciona porque el indice normaliza. Una guarda que deja de cubrir sin avisar es
// peor que no tenerla, asi que la conversion se detiene aqui y no cuando alguien se
// pregunta por que una regla dejo de estar protegida.
//
// .gitattributes declara `* text=auto eol=lf` para que git lo mantenga; esto
// comprueba el disco, que es lo que leen los dos scripts de mutacion.
// =====================================================================

/** El retorno de carro, sin escribirlo como escape: ver la nota de abajo. */
const RETORNO_DE_CARRO = String.fromCharCode(13)

const CON_FINALES = [
  ...archivos(FUENTE, ['.jsx', '.js', '.css']),
  ...archivos(join(RAIZ, 'guardas'), ['.mjs']),
  ...archivos(join(RAIZ, 'pruebas'), ['.jsx', '.js', '.mjs']),
]

for (const ruta of CON_FINALES) {
  // El CR se compara por codigo y no como "\r\n" a proposito: este archivo es
  // justo el que no puede permitirse que una herramienta le toque los escapes.
  if (textoDe(ruta).includes(RETORNO_DE_CARRO)) {
    fallar('finales-de-linea', `${corto(ruta)} tiene CRLF. El arbol es LF, y con CRLF los `
      + 'anclajes multilinea de romper.mjs dejan de encontrarse: las guardas de mutacion '
      + 'reportan "no esta" sobre codigo intacto. Normalizar antes de seguir.')
  }
}

// =====================================================================
// Resultado
// =====================================================================

const GUARDAS = ['contraste', 'supuesto-marca', 'movimiento', 'literales', 'variables',
                 'sin-cdn', 'sin-rasterizadas', 'fuentes', 'finales-de-linea']

console.log('Guardas del sistema de diseño\n')

for (const guarda of GUARDAS) {
  const suyos = fallos.filter((fallo) => fallo.guarda === guarda)
  console.log(`  ${suyos.length === 0 ? 'OK  ' : 'FALLA'}  ${guarda}`)
  for (const fallo of suyos) console.log(`         ${fallo.detalle}`)
}

if (avisos.length > 0) {
  console.log('')
  for (const aviso of avisos) console.log(`  aviso  ${aviso}`)
}

console.log('')
if (fallos.length > 0) {
  console.error(`${fallos.length} incumplimiento(s) del sistema de diseño.`)
  process.exit(1)
}
console.log('Sistema de diseño respetado.')
