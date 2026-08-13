import { useId } from 'react'

/**
 * Un campo con su etiqueta, su ayuda y su error.
 *
 * El error se pasa desde fuera porque viene del backend: los mensajes de
 * validacion y los 409 ya estan escritos para leerse en pantalla, y reescribirlos
 * en el front seria mantener dos versiones del mismo texto hasta que se
 * contradigan.
 */
export function Campo({ etiqueta, error, ayuda, children, ...resto }) {
  const id = useId()
  const idError = `${id}-error`
  const idAyuda = `${id}-ayuda`

  const clases = ['campo__control']
  if (error) clases.push('campo__control--invalido')

  return (
    <div className="campo">
      <label className="campo__etiqueta" htmlFor={id}>{etiqueta}</label>
      {children ? (
        children({ id, className: clases.join(' '), 'aria-invalid': Boolean(error) || undefined })
      ) : (
        <input
          id={id}
          className={clases.join(' ')}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={error ? idError : ayuda ? idAyuda : undefined}
          {...resto}
        />
      )}
      {ayuda && !error && <span className="campo__ayuda" id={idAyuda}>{ayuda}</span>}
      {error && <span className="campo__error" id={idError} role="alert">{error}</span>}
    </div>
  )
}
