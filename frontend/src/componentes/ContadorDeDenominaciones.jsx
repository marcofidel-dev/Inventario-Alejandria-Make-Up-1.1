import { useRef } from 'react'

import { formatearPesos } from '../pantallas/Catalogo.jsx'

/**
 * El conteo fisico del cajon, denominacion por denominacion.
 *
 * DE MAYOR A MENOR, que es como se cuenta la plata: se apilan los billetes grandes
 * primero y se termina con las monedas. Contar en el orden contrario obliga a
 * reordenar mentalmente lo que ya esta en la mano.
 *
 * ENTER BAJA A LA SIGUIENTE. En una caja se cuenta con una mano en el dinero y la
 * otra en el teclado; obligar a buscar el campo siguiente con el raton entre billete
 * y billete es lo que hace que alguien pierda la cuenta. Del ultimo campo el foco
 * pasa a `refSiguiente`, que es el boton de continuar.
 *
 * SE PUEDE DEJAR EN CERO. Un campo vacio cuenta como cero: exigir escribir once ceros
 * un dia que solo hubo transferencias seria pedir once oportunidades de equivocarse.
 *
 * Es controlado: el conteo vive en la pantalla, que es quien lo tiene que enviar. Que
 * aqui hubiera una copia del estado seria dos verdades sobre el mismo dinero.
 */
export const DENOMINACIONES = [
  100000, 50000, 20000, 10000, 5000, 2000, 1000, 500, 200, 100, 50,
]

/** Lo que suma un conteo. La unica operacion aritmetica de todo el cierre en el front. */
export function totalContado(conteo) {
  return DENOMINACIONES.reduce(
    (suma, denominacion) => suma + denominacion * (Number(conteo[denominacion]) || 0),
    0,
  )
}

/** El conteo con la forma que espera CerrarSesionPeticion: las once, con su cantidad. */
export function conteoParaEnviar(conteo) {
  return DENOMINACIONES.map((denominacion) => ({
    denominacion,
    cantidad: Number(conteo[denominacion]) || 0,
  }))
}

export function ContadorDeDenominaciones({ conteo, alCambiar, refSiguiente }) {
  const campos = useRef([])

  function alPulsarTecla(evento, indice) {
    if (evento.key !== 'Enter') return
    // Sin submit: este contador vive dentro de un flujo de varios pasos y un Enter
    // que enviara el formulario cerraria la caja a mitad del conteo.
    evento.preventDefault()
    const siguiente = campos.current[indice + 1] ?? refSiguiente?.current
    siguiente?.focus()
  }

  return (
    <div className="conteo">
      {DENOMINACIONES.map((denominacion, indice) => {
        const cantidad = Number(conteo[denominacion]) || 0
        const subtotal = denominacion * cantidad
        const idCampo = `conteo-${denominacion}`

        return (
          <div className="conteo__fila" key={denominacion}>
            <label className="conteo__denominacion monto" htmlFor={idCampo}>
              ${formatearPesos(denominacion)}
            </label>
            <input
              id={idCampo}
              ref={(elemento) => { campos.current[indice] = elemento }}
              className="campo__control conteo__cantidad monto"
              inputMode="numeric"
              autoComplete="off"
              value={conteo[denominacion] ?? ''}
              // Solo digitos: una coma o un signo menos en un conteo de billetes no
              // significa nada, y el navegador los aceptaria sin decir nada.
              onChange={(evento) => alCambiar(denominacion, evento.target.value.replace(/\D/g, ''))}
              onKeyDown={(evento) => alPulsarTecla(evento, indice)}
              aria-label={`Cuántos billetes o monedas de ${formatearPesos(denominacion)}`}
            />
            <span className="conteo__subtotal monto">
              {subtotal === 0 ? '—' : formatearPesos(subtotal)}
            </span>
          </div>
        )
      })}
    </div>
  )
}
