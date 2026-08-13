import { useCallback, useEffect, useRef, useState } from 'react'
import { Delete, Lock } from 'lucide-react'

import { CODIGOS } from '../api/cliente.js'
import { autenticacion } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'

const LARGO_PIN = 4

/**
 * Entrar al sistema.
 *
 * Cuatro decisiones que vale la pena no deshacer sin pensarlo:
 *
 * 1. Se elige el nombre de una lista, no se teclea. Un error de tipeo en el
 *    nombre da 401 y cuenta como intento fallido, asi que teclearlo es una via
 *    directa al bloqueo por equivocarse escribiendo.
 *
 * 2. El PIN se envia solo al cuarto digito. No hay boton "Entrar": en una caja se
 *    entra varias veces al dia y ese clic sobra.
 *
 * 3. El teclado fisico es el mecanismo; el numpad en pantalla es un agregado. Si
 *    el PC no es tactil, tocar botones con el raton es mas lento que teclear.
 *    Digitos, retroceso, Enter y Escape funcionan todos.
 *
 * 4. Al fallar, el mensaje es siempre el mismo y no dice si el PIN estaba mal o
 *    el usuario no existe. Se limpia el PIN, vuelve el foco, y NO se deselecciona
 *    el nombre: obligar a elegir de nuevo despues de cada error es castigar dos
 *    veces el mismo dedo torcido.
 */
export function Login({ alEntrar }) {
  const [perfiles, setPerfiles] = useState(null)
  const [errorDeCarga, setErrorDeCarga] = useState(null)
  const [nombre, setNombre] = useState(null)
  const [pin, setPin] = useState('')
  const [error, setError] = useState(null)
  const [entrando, setEntrando] = useState(false)
  const [segundosDeBloqueo, setSegundosDeBloqueo] = useState(0)

  const campoPin = useRef(null)
  const bloqueado = segundosDeBloqueo > 0

  /**
   * El PIN vive tambien en un ref, y el ref es la fuente de verdad para decidir
   * cuando enviar.
   *
   * React invoca dos veces las funciones actualizadoras de estado bajo
   * StrictMode en desarrollo, asi que un `intentar()` dentro de un `setPin(fn)`
   * enviaria el login dos veces: dos intentos por cada PIN teclado, y el bloqueo
   * a los cinco fallos llegando a la mitad de los errores reales. El ref tampoco
   * depende del agrupado de renders, asi que dos digitos en el mismo tick no se
   * pierden.
   */
  const pinActual = useRef('')

  function fijarPin(siguiente) {
    pinActual.current = siguiente
    setPin(siguiente)
  }

  function borrarUnDigito() {
    fijarPin(pinActual.current.slice(0, -1))
  }

  function volverAElegirUsuario() {
    setNombre(null)
    fijarPin('')
    setError(null)
  }

  const cargarPerfiles = useCallback(async () => {
    setErrorDeCarga(null)
    try {
      setPerfiles(await autenticacion.perfiles())
    } catch (fallo) {
      setErrorDeCarga(fallo)
    }
  }, [])

  useEffect(() => { cargarPerfiles() }, [cargarPerfiles])

  // La cuenta regresiva del bloqueo. Cuando llega a cero se reactiva todo solo:
  // que haya que recargar la pagina para volver a intentar seria otra forma de
  // hacer creer que el programa se daño.
  useEffect(() => {
    if (segundosDeBloqueo <= 0) return undefined
    const reloj = setInterval(() => setSegundosDeBloqueo((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(reloj)
  }, [segundosDeBloqueo])

  useEffect(() => {
    if (segundosDeBloqueo === 0 && !bloqueado) campoPin.current?.focus()
  }, [segundosDeBloqueo, bloqueado])

  const intentar = useCallback(async (pinCompleto) => {
    setEntrando(true)
    setError(null)
    try {
      alEntrar(await autenticacion.entrar(nombre, pinCompleto))
    } catch (fallo) {
      fijarPin('')
      if (fallo.codigo === CODIGOS.demasiadosIntentos) {
        // El dato viene del backend, no se deduce del texto del mensaje.
        setSegundosDeBloqueo(Number(fallo.datos.reintentarEnSegundos) || 0)
      }
      setError(fallo)
      campoPin.current?.focus()
    } finally {
      setEntrando(false)
    }
  }, [nombre, alEntrar])

  function agregarDigito(digito) {
    if (bloqueado || entrando) return
    if (pinActual.current.length >= LARGO_PIN) return
    const siguiente = pinActual.current + digito
    fijarPin(siguiente)
    if (siguiente.length === LARGO_PIN) intentar(siguiente)
  }

  function alTeclear(evento) {
    if (evento.key === 'Escape') {
      volverAElegirUsuario()
      return
    }
    if (bloqueado) return
    if (/^\d$/.test(evento.key)) {
      evento.preventDefault()
      agregarDigito(evento.key)
    } else if (evento.key === 'Backspace') {
      evento.preventDefault()
      borrarUnDigito()
    } else if (evento.key === 'Enter' && pinActual.current.length === LARGO_PIN) {
      evento.preventDefault()
      intentar(pinActual.current)
    }
  }

  if (errorDeCarga) {
    return (
      <main className="login">
        <div className="login__caja">
          <h1 className="login__titulo">Alejandria MakeUp</h1>
          <AvisoDeError error={errorDeCarga} alReintentar={cargarPerfiles} />
        </div>
      </main>
    )
  }

  return (
    <main className="login">
      <div className="login__caja">
        <h1 className="login__titulo">Alejandria MakeUp</h1>

        {nombre === null ? (
          <>
            <p className="texto-secundario">¿Quién entra?</p>
            <div className="login__perfiles">
              {(perfiles ?? []).map((perfil) => (
                <Boton
                  key={perfil.nombre}
                  variante="principal"
                  onClick={() => { setNombre(perfil.nombre); fijarPin(''); setError(null) }}
                >
                  {perfil.nombre}
                </Boton>
              ))}
            </div>
            {perfiles?.length === 0 && (
              <Aviso tipo="info">No hay usuarios activos para entrar.</Aviso>
            )}
          </>
        ) : (
          <>
            <p className="login__nombre">{nombre}</p>

            {/* El input recibe el teclado fisico. Es de tipo password y readOnly:
                el valor lo maneja el estado, para que el numpad y el teclado
                escriban por el mismo camino y no puedan discrepar. */}
            <input
              ref={campoPin}
              className="solo-lectores"
              type="password"
              inputMode="numeric"
              autoComplete="off"
              readOnly
              autoFocus
              aria-label="PIN"
              value={pin}
              onKeyDown={alTeclear}
            />

            <div className="login__casillas" aria-hidden="true">
              {Array.from({ length: LARGO_PIN }, (_, indice) => (
                <span
                  key={indice}
                  className={indice < pin.length ? 'login__casilla login__casilla--llena'
                                                 : 'login__casilla'}
                />
              ))}
            </div>

            {bloqueado ? (
              <Aviso tipo="error" titulo="Cuenta bloqueada">
                {error?.message}
                <span className="login__cuenta-regresiva monto">{formatearEspera(segundosDeBloqueo)}</span>
              </Aviso>
            ) : (
              error && <Aviso tipo="error">{error.message}</Aviso>
            )}

            <div className="login__numpad">
              {['7', '8', '9', '4', '5', '6', '1', '2', '3'].map((digito) => (
                <Boton key={digito} onClick={() => agregarDigito(digito)}
                       disabled={bloqueado || entrando} tabIndex={-1}>
                  {digito}
                </Boton>
              ))}
              <Boton onClick={() => agregarDigito('0')} disabled={bloqueado || entrando}
                     tabIndex={-1}>
                0
              </Boton>
              <Boton icono={Delete} aria-label="Borrar" onClick={borrarUnDigito}
                     disabled={bloqueado || entrando} tabIndex={-1} />
            </div>

            <Boton variante="plano" icono={Lock} onClick={volverAElegirUsuario}>
              Cambiar de usuario (Esc)
            </Boton>
          </>
        )}
      </div>
    </main>
  )
}

function formatearEspera(segundos) {
  const minutos = Math.floor(segundos / 60)
  const resto = segundos % 60
  return `${String(minutos).padStart(2, '0')}:${String(resto).padStart(2, '0')}`
}
