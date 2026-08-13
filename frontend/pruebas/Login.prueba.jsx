import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Login } from '../src/pantallas/Login.jsx'

/**
 * La pantalla de login concentra las reglas mas faciles de romper sin notarlo, y
 * la mas importante: que el bloqueo se explique.
 */

function respuesta(estado, cuerpo) {
  return {
    ok: estado >= 200 && estado < 300,
    status: estado,
    text: async () => JSON.stringify(cuerpo),
  }
}

const PERFILES = [{ nombre: 'Alejandra' }, { nombre: 'Camila' }]

function prepararFetch(alHacerLogin) {
  return vi.fn(async (ruta) => {
    if (ruta === '/api/v1/auth/perfiles') return respuesta(200, PERFILES)
    if (ruta === '/api/v1/auth/login') return alHacerLogin()
    throw new Error(`ruta inesperada: ${ruta}`)
  })
}

describe('Login', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', prepararFetch(() => respuesta(200, {})))
  })

  it('ofrece los nombres en vez de pedir que se teclee', async () => {
    render(<Login alEntrar={() => {}} />)

    expect(await screen.findByRole('button', { name: 'Alejandra' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Camila' })).toBeInTheDocument()
    // Sin campo de nombre: teclearlo es una via directa al bloqueo por errores
    // de tipeo, porque un nombre mal escrito da 401 y cuenta como intento.
    expect(screen.queryByLabelText('Nombre')).not.toBeInTheDocument()
  })

  it('envía el PIN al cuarto dígito, sin botón de entrar', async () => {
    const usuario = userEvent.setup()
    const alEntrar = vi.fn()
    const espia = prepararFetch(() => respuesta(200, { nombre: 'Alejandra', rol: 'DUENA', permisos: [] }))
    vi.stubGlobal('fetch', espia)

    render(<Login alEntrar={alEntrar} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))

    await usuario.keyboard('123')
    expect(espia).not.toHaveBeenCalledWith('/api/v1/auth/login', expect.anything())
    expect(screen.queryByRole('button', { name: /entrar/i })).not.toBeInTheDocument()

    await usuario.keyboard('4')
    await waitFor(() => expect(alEntrar).toHaveBeenCalledOnce())

    const [, opciones] = espia.mock.calls.find(([ruta]) => ruta === '/api/v1/auth/login')
    expect(JSON.parse(opciones.body)).toEqual({ nombre: 'Alejandra', pin: '1234' })
  })

  /**
   * La app real se monta en StrictMode, que en desarrollo invoca dos veces las
   * funciones actualizadoras de estado. Un efecto secundario dentro de un
   * `setPin(fn)` enviaba el login dos veces: dos intentos fallidos por cada PIN
   * mal teclado, y el bloqueo de cinco intentos disparandose a los tres errores
   * reales. En pantalla no se nota nada.
   */
  it('envía el login UNA sola vez, incluso bajo StrictMode', async () => {
    const usuario = userEvent.setup()
    const espia = prepararFetch(() => respuesta(401, {
      codigo: 'CREDENCIALES_INVALIDAS',
      error: 'Usuario o PIN incorrectos.',
    }))
    vi.stubGlobal('fetch', espia)

    render(
      <StrictMode>
        <Login alEntrar={() => {}} />
      </StrictMode>,
    )
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('1234')

    await screen.findByText('Usuario o PIN incorrectos.')
    const intentos = espia.mock.calls.filter(([ruta]) => ruta === '/api/v1/auth/login')
    expect(intentos).toHaveLength(1)
  })

  it('no envía un segundo intento con el PIN a medias tras un fallo', async () => {
    const usuario = userEvent.setup()
    const espia = prepararFetch(() => respuesta(401, {
      codigo: 'CREDENCIALES_INVALIDAS',
      error: 'Usuario o PIN incorrectos.',
    }))
    vi.stubGlobal('fetch', espia)

    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('1234')
    await screen.findByText('Usuario o PIN incorrectos.')

    // Si el ref del PIN no se limpiara junto con el estado, el siguiente digito
    // creeria que ya hay cuatro y enviaria otro intento de una sola pulsacion.
    await usuario.keyboard('5')
    expect(casillasLlenas()).toBe(1)
    expect(espia.mock.calls.filter(([ruta]) => ruta === '/api/v1/auth/login')).toHaveLength(1)
  })

  it('el teclado físico funciona completo: dígitos, retroceso y Escape', async () => {
    const usuario = userEvent.setup()
    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))

    await usuario.keyboard('12')
    expect(casillasLlenas()).toBe(2)

    await usuario.keyboard('{Backspace}')
    expect(casillasLlenas()).toBe(1)

    // Escape vuelve a elegir usuario.
    await usuario.keyboard('{Escape}')
    expect(await screen.findByRole('button', { name: 'Camila' })).toBeInTheDocument()
  })

  it('al fallar limpia el PIN, deja el mensaje y NO deselecciona el nombre', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', prepararFetch(() => respuesta(401, {
      codigo: 'CREDENCIALES_INVALIDAS',
      error: 'Usuario o PIN incorrectos.',
    })))

    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('9999')

    expect(await screen.findByText('Usuario o PIN incorrectos.')).toBeInTheDocument()
    // El PIN limpio para volver a intentar de una.
    expect(casillasLlenas()).toBe(0)
    // Y el nombre sigue elegido: obligar a elegirlo otra vez despues de cada
    // error es castigar dos veces el mismo dedo torcido.
    expect(screen.getByText('Alejandra')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Camila' })).not.toBeInTheDocument()
  })

  it('el mensaje de fallo no dice si el error fue el PIN o el usuario', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', prepararFetch(() => respuesta(401, {
      codigo: 'CREDENCIALES_INVALIDAS',
      error: 'Usuario o PIN incorrectos.',
    })))

    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('0000')

    const aviso = await screen.findByRole('alert')
    expect(aviso).toHaveTextContent('Usuario o PIN incorrectos.')
    expect(aviso).not.toHaveTextContent(/PIN incorrecto$/)
    expect(aviso).not.toHaveTextContent(/no existe/i)
  })

  /**
   * La regla mas importante de esta pantalla. Si el sistema deja de responder a los
   * cinco intentos sin explicar nada, quien esta del otro lado va a creer que el
   * programa se daño y va a reiniciar el equipo, o llamar a alguien.
   */
  it('cuando el bloqueo se activa lo dice, con cuenta regresiva, y deshabilita el numpad', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', prepararFetch(() => respuesta(429, {
      codigo: 'DEMASIADOS_INTENTOS',
      error: 'Esta cuenta quedó bloqueada por cinco intentos fallidos. Hay que esperar 5 minuto(s).',
      reintentarEnSegundos: 125,
    })))

    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('0000')

    expect(await screen.findByText('Cuenta bloqueada')).toBeInTheDocument()
    expect(screen.getByText(/quedó bloqueada/)).toBeInTheDocument()
    // La cuenta regresiva sale del dato del backend, no de interpretar el texto.
    expect(screen.getByText('02:05')).toBeInTheDocument()

    for (const digito of ['1', '2', '3', '0']) {
      expect(screen.getByRole('button', { name: digito })).toBeDisabled()
    }
  })

  /**
   * Con espera real de dos segundos, no con timers falsos: `waitFor` de
   * testing-library detecta timers falsos buscando el global `jest`, que con
   * vitest no existe, asi que su propio poll queda congelado y la prueba se
   * cuelga. Dos segundos de reloj de verdad cuestan menos que ese enredo.
   */
  it('la cuenta regresiva avanza y al llegar a cero reactiva el numpad', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', prepararFetch(() => respuesta(429, {
      codigo: 'DEMASIADOS_INTENTOS',
      error: 'Bloqueada.',
      reintentarEnSegundos: 2,
    })))

    render(<Login alEntrar={() => {}} />)
    await usuario.click(await screen.findByRole('button', { name: 'Alejandra' }))
    await usuario.keyboard('0000')

    expect(await screen.findByText('00:02')).toBeInTheDocument()
    expect(await screen.findByText('00:01')).toBeInTheDocument()

    // Al llegar a cero se reactiva solo: obligar a recargar la pagina seria otra
    // forma de hacer creer que el programa se daño.
    await waitFor(() => {
      expect(screen.queryByText('Cuenta bloqueada')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '1' })).toBeEnabled()
    }, { timeout: 3000 })
  }, 10000)

  it('sin servidor, lo dice y ofrece reintentar', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))

    render(<Login alEntrar={() => {}} />)

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })
})

function casillasLlenas() {
  return document.querySelectorAll('.login__casilla--llena').length
}
