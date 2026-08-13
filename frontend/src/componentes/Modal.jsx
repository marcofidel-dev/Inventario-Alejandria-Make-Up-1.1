import { useEffect, useRef } from 'react'

/**
 * Un modal. Escape cierra, el foco entra al abrir y no se escapa mientras esta
 * abierto.
 *
 * Lo del teclado no es accesibilidad de adorno: en una caja se trabaja con las
 * manos en el teclado mucho mas de lo que se cree, y un modal del que hay que
 * salir con el raton interrumpe el ritmo de quien esta cargando cien productos.
 */
export function Modal({ titulo, alCerrar, children, acciones }) {
  const caja = useRef(null)

  useEffect(() => {
    const anterior = document.activeElement
    const primero = caja.current?.querySelector(
      'input, select, textarea, button:not([disabled])',
    )
    primero?.focus()

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
      <div className="modal" role="dialog" aria-modal="true" aria-label={titulo} ref={caja}>
        <h2 className="modal__titulo">{titulo}</h2>
        {children}
        {acciones && <div className="modal__acciones">{acciones}</div>}
      </div>
    </div>
  )
}
