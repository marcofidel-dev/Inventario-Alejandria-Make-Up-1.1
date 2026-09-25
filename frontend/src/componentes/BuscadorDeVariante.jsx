import { useLayoutEffect, useMemo, useRef, useState } from 'react'

import { filtrar } from '../catalogo/useCatalogo.js'
import { Campo } from './Campo.jsx'

/** Mas resultados que esto no ayudan: quien no ve el suyo escribe una letra mas. */
const MAXIMO_SUGERENCIAS = 8

/** Lo que se deja libre contra el borde de la ventana, en pixeles. */
const HOLGURA = 8

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
export function BuscadorDeVariante({ filas, valor, alElegir, etiqueta, error, indice,
  etiquetaAccesible }) {
  const [texto, setTexto] = useState('')
  const [abierto, setAbierto] = useState(false)
  const [resaltado, setResaltado] = useState(0)
  const entrada = useRef(null)
  const lista = useRef(null)
  const cierreDiferido = useRef(null)
  const [ancla, setAncla] = useState(null)

  const elegida = useMemo(
    () => filas.find((fila) => String(fila.id) === String(valor)),
    [filas, valor],
  )

  const sugerencias = useMemo(() => {
    if (!texto.trim()) return []
    return filtrar(filas, { texto }).slice(0, MAXIMO_SUGERENCIAS)
  }, [filas, texto])

  /**
   * DONDE SE PINTA LA LISTA, EN COORDENADAS DE VENTANA.
   *
   * La lista se posiciona `fixed` y no `absolute` porque en la captura de compras vive
   * dentro de una celda de una tabla que esta dentro de un contenedor con scroll: un
   * hijo absoluto lo recorta el primer ancestro que no sea `overflow: visible`, y el
   * desplegable quedaba cortado a media fila. Sacarlo del flujo es la unica forma de
   * que ningun ancestro pueda recortarlo, hoy y cuando alguien envuelva la tabla en
   * otra cosa.
   *
   * Se mide en `useLayoutEffect` —antes de pintar— para que no haya un fotograma con
   * la lista en la esquina. Y se vuelve a medir con el scroll de CUALQUIER contenedor
   * (de ahi el `true` de la fase de captura), porque si no se queda flotando donde
   * estaba el campo.
   *
   * Abre hacia el lado donde hay mas sitio. En una factura de cuarenta lineas las
   * ultimas estan siempre abajo, y hacia abajo no cabrian ni dos sugerencias.
   */
  useLayoutEffect(() => {
    if (!abierto || sugerencias.length === 0) return undefined

    const medir = () => {
      const campo = entrada.current?.getBoundingClientRect()
      const caja = lista.current
      if (!campo || !caja) return

      // EL ANCHO NO ES EL DEL CAMPO. En la captura de compras el campo es una celda
      // de la columna "Variante" y mide unos 280px, mientras que una descripcion como
      // "Maybelline|Labial mate Superstay|Rojo clásico|5 ml" necesita mas: cada opcion
      // se partia en dos lineas, y ocho opciones de dos lineas no caben en ninguna
      // altura razonable. El minimo sale del CSS —o sea de los tokens— y no de un
      // numero escrito aqui.
      const minimo = parseFloat(getComputedStyle(caja).minWidth) || 0
      const ancho = Math.max(campo.width, minimo)
      // Y si el campo esta cerca del borde derecho, la lista se corre para no salirse.
      const izquierda = Math.min(campo.left, window.innerWidth - ancho - HOLGURA)

      // LA ALTURA SALE DEL ESPACIO QUE HAY, NO DE UNA CUENTA DE FILAS. Antes era
      // `alto-de-fila x 8`, que da 288px y supone que cada opcion ocupa una linea:
      // con las que ocupan dos, la lista se quedaba con scroll propio mostrando cinco
      // y media aunque debajo hubiera 438px libres.
      const espacioAbajo = window.innerHeight - campo.bottom - HOLGURA
      const espacioArriba = campo.top - HOLGURA
      const haciaArriba = espacioAbajo < espacioArriba

      setAncla({
        left: Math.max(HOLGURA, izquierda),
        width: ancho,
        maxHeight: Math.max(haciaArriba ? espacioArriba : espacioAbajo, 0),
        ...(haciaArriba
          ? { bottom: window.innerHeight - campo.top }
          : { top: campo.bottom }),
      })
    }

    medir()
    window.addEventListener('scroll', medir, true)
    window.addEventListener('resize', medir)
    return () => {
      window.removeEventListener('scroll', medir, true)
      window.removeEventListener('resize', medir)
    }
  }, [abierto, sugerencias.length])

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
            // En una factura de compra hay lineas numeradas; en el mostrador hay un
            // solo buscador y "la linea 1" no significaria nada.
            aria-label={etiquetaAccesible ?? `Variante de la línea ${indice + 1}`}
            placeholder={elegida ? descripcionDe(elegida) : 'Buscar producto o tono…'}
            onChange={(e) => { setTexto(e.target.value); setAbierto(true); setResaltado(0) }}
            // Volver al buscador CANCELA el cierre pendiente. Sin esto, el cierre que
            // dejo programado el blur anterior se dispara cuando ya se esta escribiendo
            // otra vez y se lleva por delante la lista: la siguiente tecla la reabre,
            // pero un Enter en ese hueco no elige nada. En el mostrador eso es teclear
            // el producto siguiente y que no pase nada, una de cada tantas veces.
            onFocus={() => { clearTimeout(cierreDiferido.current); setAbierto(true) }}
            // El cierre se retrasa para que un clic sobre una sugerencia llegue a
            // dispararse: sin esto, el blur la desmonta antes del click.
            onBlur={() => { cierreDiferido.current = setTimeout(() => setAbierto(false), 150) }}
            onKeyDown={alTeclear}
          />
        )}
      </Campo>

      {elegida && !texto && <p className="buscador__elegida">{descripcionDe(elegida)}</p>}

      {abierto && sugerencias.length > 0 && (
        <ul className="buscador__lista" role="listbox" ref={lista} style={ancla ?? undefined}>
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

/**
 * Como se nombra una variante en pantalla: LA CADENA QUE ARMO EL BACKEND, tal cual.
 *
 * Antes esto la reconstruia con sus propias partes y su propio separador, y por eso
 * el buscador decia "Montoc · Polvos sueltos" mientras el recibo de la misma venta
 * decia "Montoc|Polvos sueltos". Dos implementaciones de la misma cadena divergen
 * siempre; lo unico que cambia es cuanto tardan. `Descripcion.de()` es la unica que
 * la arma —la congela en venta_item y la imprime en el recibo—, y el front la
 * muestra.
 *
 * Queda como funcion y no como acceso directo al campo para tener un solo sitio
 * donde poner el respaldo: una fila que venga sin descripcion no puede dejar la
 * linea en blanco.
 */
export function descripcionDe(fila) {
  return fila.descripcion ?? ''
}
