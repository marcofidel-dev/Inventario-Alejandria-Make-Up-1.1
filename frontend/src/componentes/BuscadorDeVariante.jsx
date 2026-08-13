import { useMemo, useRef, useState } from 'react'

import { filtrar } from '../catalogo/useCatalogo.js'
import { Campo } from './Campo.jsx'

/** Mas resultados que esto no ayudan: quien no ve el suyo escribe una letra mas. */
const MAXIMO_SUGERENCIAS = 8

/**
 * Buscar una variante y elegirla con el teclado.
 *
 * POR QUE NO ES UN <select> COMO EN LA CARGA INICIAL: la carga inicial se hace una
 * vez con el inventario entero delante y un desplegable alcanza. Aqui se esta
 * copiando una factura contra un catalogo que en unos meses tiene cientos de
 * variantes, y "buscar" en un desplegable de cientos de opciones no es buscar.
 * SelectorConAlta tampoco sirve: es un select con alta, no un buscador.
 *
 * FILTRA EN MEMORIA sobre el catalogo ya cargado, con la misma funcion `filtrar`
 * que usa la pantalla de catalogo. Ni una llamada por tecla: eso funciona en
 * desarrollo con tres productos y se cae en el mostrador con el inventario real.
 *
 * El teclado es el mecanismo: flechas para moverse, Enter para elegir, Escape para
 * cerrar. El raton funciona, pero quien esta metiendo cuarenta lineas de una
 * factura no puede estar alternando entre teclado y raton en cada una.
 */
export function BuscadorDeVariante({ filas, valor, alElegir, etiqueta, error, indice }) {
  const [texto, setTexto] = useState('')
  const [abierto, setAbierto] = useState(false)
  const [resaltado, setResaltado] = useState(0)
  const entrada = useRef(null)

  const elegida = useMemo(
    () => filas.find((fila) => String(fila.id) === String(valor)),
    [filas, valor],
  )

  const sugerencias = useMemo(() => {
    if (!texto.trim()) return []
    return filtrar(filas, { texto }).slice(0, MAXIMO_SUGERENCIAS)
  }, [filas, texto])

  function elegir(fila) {
    if (!fila) return
    alElegir(String(fila.id))
    setTexto('')
    setAbierto(false)
    setResaltado(0)
  }

  function alTeclear(evento) {
    if (!abierto || sugerencias.length === 0) {
      if (evento.key === 'Escape') { setTexto(''); setAbierto(false) }
      return
    }

    if (evento.key === 'ArrowDown') {
      evento.preventDefault()
      setResaltado((actual) => (actual + 1) % sugerencias.length)
    } else if (evento.key === 'ArrowUp') {
      evento.preventDefault()
      setResaltado((actual) => (actual - 1 + sugerencias.length) % sugerencias.length)
    } else if (evento.key === 'Enter') {
      evento.preventDefault()
      elegir(sugerencias[resaltado])
    } else if (evento.key === 'Escape') {
      evento.preventDefault()
      setTexto('')
      setAbierto(false)
    }
  }

  return (
    <div className="buscador">
      <Campo etiqueta={etiqueta ?? ''} error={error}>
        {(props) => (
          <input
            {...props}
            ref={entrada}
            data-buscador="true"
            value={texto}
            role="combobox"
            aria-expanded={abierto && sugerencias.length > 0}
            aria-autocomplete="list"
            aria-label={`Variante de la línea ${indice + 1}`}
            placeholder={elegida ? descripcionDe(elegida) : 'Buscar producto o tono…'}
            onChange={(e) => { setTexto(e.target.value); setAbierto(true); setResaltado(0) }}
            onFocus={() => setAbierto(true)}
            // El cierre se retrasa para que un clic sobre una sugerencia llegue a
            // dispararse: sin esto, el blur la desmonta antes del click.
            onBlur={() => setTimeout(() => setAbierto(false), 150)}
            onKeyDown={alTeclear}
          />
        )}
      </Campo>

      {elegida && !texto && <p className="buscador__elegida">{descripcionDe(elegida)}</p>}

      {abierto && sugerencias.length > 0 && (
        <ul className="buscador__lista" role="listbox">
          {sugerencias.map((fila, posicion) => (
            <li key={fila.id}>
              <button
                type="button"
                role="option"
                aria-selected={posicion === resaltado}
                className="buscador__opcion"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => elegir(fila)}
              >
                {descripcionDe(fila)}
              </button>
            </li>
          ))}
        </ul>
      )}

      {abierto && texto.trim() && sugerencias.length === 0 && (
        <p className="buscador__vacio">Ningún producto coincide.</p>
      )}
    </div>
  )
}

/** Marca, producto, tono y tamaño: lo que distingue una variante de otra. */
export function descripcionDe(fila) {
  return [fila.marcaNombre, fila.productoNombre, fila.tono, fila.tamano]
    .filter(Boolean)
    .join(' · ')
}
