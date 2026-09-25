import { useEffect, useRef } from 'react'

/**
 * Un modal. Escape cierra, el foco entra al abrir y no se escapa mientras esta
 * abierto.
 *
 * Lo del teclado no es accesibilidad de adorno: en una caja se trabaja con las
 * manos en el teclado mucho mas de lo que se cree, y un modal del que hay que
 * salir con el raton interrumpe el ritmo de quien esta cargando cien productos.
 *
 * `amplio` es para los formularios de captura —producto y variante durante una
 * compra—: usan el ancho de la pantalla y reparten los campos en dos columnas, para
 * que quepan sin desplazamiento interno. Los demas modales son confirmaciones de
 * dos lineas y a 56rem de ancho se leerian peor, no mejor.
 */
export function Modal({ titulo, alCerrar, children, acciones, amplio = false }) {
  const caja = useRef(null)

  useEffect(() => {
    const anterior = document.activeElement

    // El foco entra al primer control, PERO SOLO SI NADIE LO RECLAMO YA. Un campo
    // con autoFocus se enfoca al montar, antes que este efecto, y pisarselo mandaba
    // el foco al primer control aunque no fuera el que hay que llenar: al encadenar
    // producto -> variante durante una compra, el producto ya viene elegido y el
    // cursor tiene que caer en el tono. Se notaba solo tecleando, que es justo lo
    // que nadie hace al revisar un modal.
    if (!caja.current?.contains(document.activeElement)) {
      const primero = caja.current?.querySelector(
        'input, select, textarea, button:not([disabled])',
      )
      primero?.focus()
    }

    function alPulsarTecla(evento) {
      if (evento.key === 'Escape') {
        evento.stopPropagation()
        alCerrar()
        return
      }
      if (evento.key !== 'Tab' || !caja.current) return

      const enfocables = [...caja.current.querySelectorAll(
        'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])',
      )]
      if (enfocables.length === 0) return

      const primeroDeLaLista = enfocables[0]
      const ultimo = enfocables[enfocables.length - 1]

      if (!evento.shiftKey && document.activeElement === ultimo) {
        evento.preventDefault()
        primeroDeLaLista.focus()
      } else if (evento.shiftKey && document.activeElement === primeroDeLaLista) {
        evento.preventDefault()
        ultimo.focus()
      }
    }

    document.addEventListener('keydown', alPulsarTecla, true)
    return () => {
      document.removeEventListener('keydown', alPulsarTecla, true)
      if (anterior instanceof HTMLElement) anterior.focus()
    }
  }, [alCerrar])

  return (
    <div className="modal-fondo" onMouseDown={(e) => e.target === e.currentTarget && alCerrar()}>
      <div className={amplio ? 'modal modal--amplio' : 'modal'} role="dialog"
           aria-modal="true" aria-label={titulo} ref={caja}>
        <h2 className="modal__titulo">{titulo}</h2>
        {children}
        {acciones && <div className="modal__acciones">{acciones}</div>}
      </div>
    </div>
  )
}
