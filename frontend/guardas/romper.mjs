#!/usr/bin/env node
/**
 * Comprueba que cada guarda falla de verdad al romperla.
 *
 * Una guarda que pasa no demuestra nada: puede estar pasando porque no mira
 * donde dice mirar. Un `matchAll` con un parentesis de mas, una ruta que no
 * existe, un filtro que descarta todos los archivos — todo eso da OK. Igual que
 * en el backend, la unica prueba de que una regla es portante es romperla a
 * proposito y ver caer la guarda con un mensaje que se entienda.
 *
 * Cada caso muta un archivo, corre `verificar.mjs`, exige que salga con codigo
 * distinto de cero y que la guarda esperada aparezca como FALLA, y restaura el
 * archivo. La restauracion va en `finally`: dejar el sistema de diseno roto por
 * un error de esta comprobacion seria peor que no tenerla.
 *
 * npm run guardas:romper
 */
import { spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const RAIZ = fileURLToPath(new URL('..', import.meta.url))
const VERIFICAR = join(RAIZ, 'guardas', 'verificar.mjs')

const TOKENS = join(RAIZ, 'src', 'estilos', 'tokens.css')
const COMPONENTES = join(RAIZ, 'src', 'componentes', 'componentes.css')
const FUENTES = join(RAIZ, 'src', 'estilos', 'fuentes.js')

/** Reemplazo textual que tiene que encontrar su objetivo: si no, el caso miente. */
function sustituir(de, a) {
  return (contenido) => {
    if (!contenido.includes(de)) {
      throw new Error(`el caso esperaba encontrar ${JSON.stringify(de)} y no está. `
        + 'La comprobación quedó desalineada del archivo.')
    }
    return contenido.replace(de, a)
  }
}

const CASOS = [
  {
    guarda: 'contraste',
    porque: 'texto blanco sobre los rosas de la marca, que es exactamente lo ilegible',
    archivo: TOKENS,
    romper: sustituir('--texto-sobre-marca:', '--texto-sobre-marca: #FFFFFF; --descartado:'),
  },
  {
    guarda: 'supuesto-marca',
    porque: 'un rosa tan oscuro que ya aguantaría texto claro, o sea que la regla cambió',
    archivo: TOKENS,
    romper: sustituir('--marca-rosa-fuerte:', '--marca-rosa-fuerte: #6B1030; --descartado:'),
  },
  {
    guarda: 'movimiento',
    porque: 'una duración de 400ms, por encima del techo de 240',
    archivo: TOKENS,
    romper: sustituir('--mov-medio:       240ms;', '--mov-medio:       400ms;'),
  },
  {
    guarda: 'literales',
    porque: 'un color hexadecimal escrito a mano fuera de tokens.css',
    archivo: COMPONENTES,
    romper: (contenido) => `${contenido}\n.colado { color: #C0392B; }\n`,
  },
  {
    guarda: 'literales',
    porque: 'una longitud en px escrita a mano, que se sale de la escala de espaciado',
    archivo: COMPONENTES,
    romper: (contenido) => `${contenido}\n.colado { padding: 13px; }\n`,
  },
  {
    guarda: 'literales',
    porque: 'una transición de 400ms escrita directamente en un componente',
    archivo: COMPONENTES,
    romper: (contenido) => `${contenido}\n.colado { transition: opacity 400ms; }\n`,
  },
  {
    guarda: 'variables',
    porque: 'un var(--acion) mal escrito, que no da error: la propiedad no se aplica',
    archivo: COMPONENTES,
    romper: (contenido) => `${contenido}\n.colado { color: var(--acion); }\n`,
  },
  {
    guarda: 'sin-cdn',
    porque: 'una fuente traída de Google, que deja la tienda dependiendo del internet',
    archivo: COMPONENTES,
    romper: (contenido) =>
      `@import url("https://fonts.googleapis.com/css2?family=Inter");\n${contenido}`,
  },
  {
    guarda: 'sin-rasterizadas',
    porque: 'un icono en .png, que se ve borroso en cuanto cambia la escala de Windows',
    archivo: COMPONENTES,
    romper: (contenido) => `${contenido}\n.colado { background-image: url("iconos/lupa.png"); }\n`,
  },
  {
    guarda: 'fuentes',
    porque: 'la familia que declara tokens.css dejando de estar importada, '
      + 'que el navegador sustituye en silencio',
    archivo: FUENTES,
    romper: (contenido) => contenido.replaceAll('source-sans-3', 'source-sans-9'),
  },
]

function correrVerificar() {
  const resultado = spawnSync(process.execPath, [VERIFICAR], { encoding: 'utf8' })
  return {
    codigo: resultado.status,
    salida: `${resultado.stdout ?? ''}${resultado.stderr ?? ''}`,
  }
}

function laGuardaFalla(salida, guarda) {
  return salida.split('\n').some((linea) =>
    linea.includes('FALLA') && linea.trim().endsWith(guarda))
}

console.log('Comprobando que las guardas caen al romperlas\n')

// Punto de partida: con el árbol intacto, todo tiene que estar en verde. Si no,
// cualquier caso de abajo "pasaría" por un fallo que ya estaba.
const inicial = correrVerificar()
if (inicial.codigo !== 0) {
  console.error('El árbol ya incumple el sistema de diseño; arregla eso antes.\n')
  console.error(inicial.salida)
  process.exit(1)
}

let fallidos = 0

for (const caso of CASOS) {
  const original = readFileSync(caso.archivo, 'utf8')
  let veredicto
  try {
    writeFileSync(caso.archivo, caso.romper(original))
    const { codigo, salida } = correrVerificar()

    if (codigo === 0) {
      veredicto = { ok: false, motivo: 'verificar.mjs salió con 0: la guarda no vio nada' }
    } else if (!laGuardaFalla(salida, caso.guarda)) {
      veredicto = {
        ok: false,
        motivo: `verificar.mjs falló pero no marcó "${caso.guarda}" como FALLA; `
          + 'cayó otra guarda, así que este caso no prueba la que dice probar',
      }
    } else {
      veredicto = { ok: true, mensaje: primerDetalle(salida, caso.guarda) }
    }
  } catch (fallo) {
    veredicto = { ok: false, motivo: fallo.message }
  } finally {
    writeFileSync(caso.archivo, original)
  }

  if (veredicto.ok) {
    console.log(`  cae    ${caso.guarda} — ${caso.porque}`)
    console.log(`         ${veredicto.mensaje}`)
  } else {
    fallidos += 1
    console.log(`  NO CAE ${caso.guarda} — ${caso.porque}`)
    console.log(`         ${veredicto.motivo}`)
  }
}

/** La primera línea de detalle de esa guarda, para ver que el mensaje se entiende. */
function primerDetalle(salida, guarda) {
  const lineas = salida.split('\n')
  const indice = lineas.findIndex((linea) =>
    linea.includes('FALLA') && linea.trim().endsWith(guarda))
  const detalle = lineas[indice + 1]?.trim() ?? ''
  return detalle.length > 150 ? `${detalle.slice(0, 150)}…` : detalle
}

// Y que el árbol quedó como estaba.
const final = correrVerificar()
if (final.codigo !== 0) {
  console.error('\nEl árbol quedó roto después de restaurar. Revisa con git diff.')
  process.exit(1)
}

console.log('')
if (fallidos > 0) {
  console.error(`${fallidos} guarda(s) no caen al romperlas: no están protegiendo nada.`)
  process.exit(1)
}
console.log(`Las ${CASOS.length} roturas caen donde deben, y el árbol quedó intacto.`)
