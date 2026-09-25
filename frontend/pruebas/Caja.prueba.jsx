import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { fetchFalso, movimientoDePrueba, sesionAbierta, sesionCerrada } from './ayudas.jsx'

const { Caja } = await import('../src/pantallas/Caja.jsx')
const { caja: apiCaja } = await import('../src/api/endpoints.js')

const SESION = sesionAbierta()

/**
 * Todo lo que hay en pantalla, incluido lo que no esta en el HTML.
 *
 * innerHTML NO trae el contenido de un campo controlado por React: `value` es una
 * propiedad del DOM y no un atributo. Una prueba que solo mirara el HTML daria verde
 * con el importe todavia escrito en el formulario, que es exactamente uno de los
 * sitios donde no puede quedarse.
 */
function todoLoVisible() {
  const campos = [...document.querySelectorAll('input, textarea')]
    .map((campo) => campo.value)
    .join(' ')
  return `${document.body.innerHTML} ${campos}`
}

/**
 * El backend responde por ruta. fetchFalso resuelve por startsWith y se queda con la
 * primera que encaja, asi que las mas especificas van primero.
 */
function montar({
  sesion = SESION,
  movimientos = [],
  historial,
  sugerencia = { baseSugerida: 263000, origen: 'Base dejada por la sesión C-000041' },
  alCerrar,
  alAbrir,
} = {}) {
  const registrados = [...movimientos]

  const espia = fetchFalso({
    '/api/v1/caja/sesiones/actual': () => (sesion
      ? { cuerpo: sesion }
      : { estado: 404, cuerpo: { codigo: 'NO_ENCONTRADO', error: 'No hay ninguna sesión.' } }),

    '/api/v1/caja/sesiones/sugerencia-apertura': { cuerpo: sugerencia },

    [`/api/v1/caja/sesiones/${sesion?.id ?? 0}/movimientos`]: () => ({ cuerpo: registrados }),

    [`/api/v1/caja/sesiones/${sesion?.id ?? 0}/cierre`]: alCerrar ?? { cuerpo: arqueo() },

    [`/api/v1/caja/sesiones/${sesion?.id ?? 0}/notas`]: { estado: 201, cuerpo: { id: 7 } },

    // GET del historial y POST de apertura comparten prefijo: se distinguen por metodo.
    '/api/v1/caja/sesiones': (ruta, opciones) => (opciones?.method === 'POST'
      ? (alAbrir ?? { estado: 201, cuerpo: sesionAbierta() })
      : { cuerpo: historial ?? [sesion, ...(sesion ? [] : [])].filter(Boolean) }),

    '/api/v1/caja/movimientos': (ruta, opciones) => {
      const enviado = JSON.parse(opciones.body)
      registrados.push(movimientoDePrueba({
        id: registrados.length + 1,
        tipo: enviado.tipo,
        monto: enviado.monto,
        concepto: enviado.concepto,
      }))
      return { estado: 201, cuerpo: registrados[registrados.length - 1] }
    },
  })

  vi.stubGlobal('fetch', espia)
  render(<Caja />)
  return espia
}

function arqueo(ajustes = {}) {
  return {
    sesion: sesionCerrada({
      id: 42, consecutivo: 'C-000042',
      efectivoEsperado: 292700, efectivoContado: 292200, diferencia: -500,
      ...ajustes,
    }),
    ventasPorMetodo: [],
  }
}

/** Registra un movimiento desde el modal, de principio a fin. */
async function registrar(usuario, { tipo, monto, concepto }) {
  await usuario.click(await screen.findByRole('button', { name: 'Registrar movimiento' }))
  await usuario.click(screen.getByRole('radio', { name: new RegExp(tipo) }))
  await usuario.type(screen.getByLabelText('Monto'), String(monto))
  await usuario.type(screen.getByLabelText(/¿/), concepto)
  await usuario.click(screen.getByRole('button', { name: 'Registrar' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
}

describe('el monto no llega a la pantalla', () => {
  /**
   * LA REGLA, AFIRMADA EN SU SITIO. La pantalla no puede filtrar el monto porque
   * nunca lo ve: se descarta en api/endpoints.js. Comprobarlo aqui, sobre el modulo
   * de la API y no sobre el DOM, es lo que hace que la garantia sea estructural — un
   * dia alguien agrega una columna a la tabla y no hay nada que mostrar.
   */
  it('la API entrega los movimientos sin su monto', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/caja/sesiones/42/movimientos': {
        cuerpo: [movimientoDePrueba({ monto: -50000 })],
      },
      '/api/v1/caja/movimientos': { estado: 201, cuerpo: movimientoDePrueba({ monto: -50000 }) },
    }))

    const [leido] = await apiCaja.movimientos(42)
    expect(leido).not.toHaveProperty('monto')
    expect(leido.concepto).toBe('Consignación')

    const registrado = await apiCaja.registrarMovimiento({
      tipo: 'RETIRO', monto: 50000, concepto: 'Consignación',
    })
    expect(registrado).not.toHaveProperty('monto')
  })
})

describe('la caja con la sesión abierta', () => {
  /**
   * LA PRUEBA DEL PUNTO 1. Base inicial + suma de movimientos ES el efectivo
   * esperado. El front conoce la base porque el mismo la envio al abrir, asi que una
   * lista que muestre los montos —o que los guarde para mostrarlos— reconstruye al
   * centavo el numero que el cierre a ciegas existe para ocultar.
   */
  it('ningún importe de los movimientos registrados queda en la pantalla', async () => {
    const usuario = userEvent.setup()
    montar()

    await registrar(usuario, { tipo: 'Sacar plata', monto: 50000, concepto: 'Consignación' })
    await registrar(usuario, { tipo: 'Pagar algo', monto: 12000, concepto: 'Domicilio' })
    await registrar(usuario, { tipo: 'Meter plata', monto: 8000, concepto: 'Sencillo' })

    // Los tres movimientos SI se ven: que hubo, cuando y de quien.
    expect(await screen.findByText('Consignación')).toBeInTheDocument()
    expect(screen.getByText('Domicilio')).toBeInTheDocument()
    expect(screen.getByText('Sencillo')).toBeInTheDocument()

    // De cuánto, nunca. Ni formateado, ni crudo, ni dentro de un campo.
    const visible = todoLoVisible()
    for (const importe of ['50.000', '12.000', '8.000', '50000', '12000', '8000']) {
      expect(visible).not.toContain(importe)
    }
  })

  it('no muestra ningún total de los movimientos', async () => {
    montar({ movimientos: [
      movimientoDePrueba({ id: 1, monto: -50000, concepto: 'Consignación' }),
      movimientoDePrueba({ id: 2, tipo: 'INGRESO', monto: 20000, concepto: 'Sencillo' }),
    ] })

    expect(await screen.findByText('Consignación')).toBeInTheDocument()
    expect(screen.queryByText(/total/i)).not.toBeInTheDocument()
    expect(todoLoVisible()).not.toContain('30.000')
  })

  it('nombra los tipos por lo que se hace, con el nombre corto debajo', async () => {
    montar({ movimientos: [movimientoDePrueba({ tipo: 'GASTO', concepto: 'Domicilio' })] })

    expect(await screen.findByText('Pagar algo con plata de la caja')).toBeInTheDocument()
    // El nombre corto, no el identificador: "Gasto" y nunca "GASTO".
    expect(screen.getByText('Gasto')).toBeInTheDocument()
    expect(screen.queryByText('GASTO')).not.toBeInTheDocument()
  })
})

describe('el historial', () => {
  /**
   * La fuga que destapo CierreACiegasTest, entrando por la pantalla en vez de por el
   * endpoint: el baseSiguiente de la ultima sesion cerrada ES el base_inicial de la
   * que esta en curso. El fixture lo trae poblado a proposito, simulando un backend
   * que dejara de omitirlo.
   */
  it('no muestra la base siguiente de una sesión cerrada', async () => {
    const cerrada = sesionCerrada({ baseSiguiente: 263000 })
    montar({ historial: [SESION, cerrada] })

    expect(await screen.findByText('C-000041')).toBeInTheDocument()
    expect(todoLoVisible()).not.toContain('263.000')
    expect(todoLoVisible()).not.toContain('263000')
  })

  it('se corta en diez sesiones y ofrece ver todas', async () => {
    const muchas = Array.from({ length: 14 }, (unused, indice) => sesionCerrada({
      id: 100 + indice,
      consecutivo: `C-0001${String(indice).padStart(2, '0')}`,
    }))
    montar({ historial: [SESION, ...muchas] })

    expect(await screen.findByText('C-000100')).toBeInTheDocument()
    expect(screen.queryByText('C-000110')).not.toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Ver todas (14)' }))
    expect(screen.getByText('C-000110')).toBeInTheDocument()
  })

  it('marca "sin explicar" la sesión con diferencia y sin ninguna nota', async () => {
    montar({ historial: [
      SESION,
      sesionCerrada({ id: 41, consecutivo: 'C-000041', diferencia: -500, notas: [] }),
      sesionCerrada({
        id: 40, consecutivo: 'C-000040', diferencia: -700,
        notas: [{ id: 1, texto: 'Faltó registrar un domicilio', fecha: '2026-08-12T20:10:00', usuario: 'Alejandra' }],
      }),
    ] })

    const sinExplicar = await screen.findAllByText('sin explicar')
    expect(sinExplicar).toHaveLength(1)
    expect(screen.getByText(/Faltó registrar un domicilio/)).toBeInTheDocument()
  })

  /**
   * El backend ya filtra por permiso: la EMPLEADA solo recibe las suyas. Volver a
   * filtrar aqui seria una segunda copia de la tabla de permisos esperando a
   * discrepar, asi que la pantalla pinta lo que le entregaron, venga de quien venga.
   */
  it('pinta las sesiones de otra persona sin filtrarlas', async () => {
    montar({ historial: [
      SESION,
      sesionCerrada({ id: 39, consecutivo: 'C-000039', usuarioApertura: 'Alejandra' }),
    ] })

    expect(await screen.findByText('C-000039')).toBeInTheDocument()
  })
})

describe('la caja olvidada de un día anterior', () => {
  it('bloquea todo lo demás y dice de qué fecha es', async () => {
    montar({
      sesion: sesionAbierta({ esDeUnDiaAnterior: true, fechaApertura: '2026-08-11T08:12:00' }),
      historial: [sesionCerrada()],
    })

    expect(await screen.findByText(/Quedó abierta la caja del 2026-08-11/)).toBeInTheDocument()

    // Nada mas: ni movimientos, ni historial, ni forma de seguir operando.
    expect(screen.queryByRole('button', { name: 'Registrar movimiento' })).not.toBeInTheDocument()
    expect(screen.queryByText('Sesiones cerradas')).not.toBeInTheDocument()
  })
})

describe('sin caja abierta', () => {
  it('lo primero es abrirla, con la base que dejó el último cierre', async () => {
    montar({ sesion: null, historial: [] })

    expect(await screen.findByRole('heading', { name: 'Abrir la caja' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Base inicial')).toHaveValue('263000'))
    expect(screen.getByText('Base dejada por la sesión C-000041')).toBeInTheDocument()
  })

  it('deja la base en blanco cuando no hay cierre previo', async () => {
    montar({
      sesion: null,
      historial: [],
      sugerencia: { baseSugerida: 0, origen: 'No hay sesiones cerradas previas' },
    })

    expect(await screen.findByRole('heading', { name: 'Abrir la caja' })).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByText('No hay sesiones cerradas previas')).toBeInTheDocument())
    expect(screen.getByLabelText('Base inicial')).toHaveValue('')
  })
})

describe('el cierre', () => {
  async function llegarAlConteo(usuario) {
    await usuario.click(await screen.findByRole('button', { name: 'Cerrar la caja' }))
  }

  async function contar(usuario, conteo) {
    for (const [denominacion, cantidad] of Object.entries(conteo)) {
      await usuario.type(
        screen.getByLabelText(new RegExp(`de ${denominacion.replace('.', '\\.')}$`)),
        String(cantidad),
      )
    }
  }

  /**
   * El nucleo del cierre a ciegas visto desde la pantalla: hasta que el conteo no se
   * envio, el sistema no ha dicho una palabra. Quien cuenta sabiendo el resultado
   * esperado cuenta hasta que le cuadre.
   */
  it('no muestra nada del sistema hasta que el conteo está enviado', async () => {
    const usuario = userEvent.setup()
    montar()
    await llegarAlConteo(usuario)

    await contar(usuario, { '100.000': 2, '50.000': 1 })

    // Paso 1: se ve lo contado y solo lo contado.
    expect(screen.getByText('250.000')).toBeInTheDocument()
    expect(todoLoVisible()).not.toContain('292.700')

    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))

    // Paso 2: la confirmacion repite lo contado. Sigue sin haber nada del sistema.
    expect(screen.getByText(/Vas a registrar/)).toBeInTheDocument()
    expect(todoLoVisible()).not.toContain('292.700')

    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    // Y ahora si, en la respuesta del envio y no antes.
    expect(await screen.findByText('292.700')).toBeInTheDocument()
    expect(screen.getByText('292.200')).toBeInTheDocument()
  })

  /**
   * Dos clics seguidos, que es lo que hace un raton con doble pulsacion o un dedo
   * nervioso. Lo que impide el segundo envio es `ocupado` en el boton, y nada mas:
   * React vacia el estado entre dos eventos discretos, asi que para el segundo clic
   * el boton ya esta deshabilitado.
   *
   * Un cierre doble responde 409 la segunda vez y no corrompe nada, pero deja a quien
   * cierra mirando un error que provoco sin saberlo, en el momento menos oportuno.
   */
  it('el cierre se envía una sola vez aunque se pulse dos veces seguidas', async () => {
    const usuario = userEvent.setup()
    let resolver
    const enVuelo = new Promise((cumplir) => { resolver = cumplir })

    const espia = montar({ alCerrar: () => enVuelo })
    await llegarAlConteo(usuario)
    await contar(usuario, { '100.000': 2 })
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))

    const boton = screen.getByRole('button', { name: 'Cerrar la caja' })
    fireEvent.click(boton)
    fireEvent.click(boton)

    const cierres = espia.mock.calls.filter(([ruta]) => ruta.endsWith('/cierre'))
    expect(cierres).toHaveLength(1)

    // Y una vez que React si repinta, el boton lo dice con palabras y se bloquea.
    expect(await screen.findByRole('button', { name: 'Cerrando…' })).toBeDisabled()

    resolver({ cuerpo: arqueo() })
    expect(await screen.findByText('292.700')).toBeInTheDocument()
  })

  it('distingue faltante de sobrante y no lo presenta como un error', async () => {
    const usuario = userEvent.setup()
    montar()
    await llegarAlConteo(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))
    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    expect(await screen.findByText('faltaron')).toBeInTheDocument()

    // Un hecho, no una acusacion: sin fondo de alarma y sin rol de alerta.
    expect(document.querySelector('.aviso--error')).toBeNull()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('dice "sobraron" cuando la diferencia es a favor', async () => {
    const usuario = userEvent.setup()
    montar({ alCerrar: { cuerpo: arqueo({ efectivoContado: 293200, diferencia: 500 }) } })
    await llegarAlConteo(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))
    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    expect(await screen.findByText('sobraron')).toBeInTheDocument()
  })

  /**
   * La nota aparece cuando ya hay algo que explicar, no antes. Pedirla en el paso 2
   * —cuando todavia no se sabe si habra diferencia— captura "normal" y despues
   * aparece el faltante.
   */
  it('con diferencia pide la nota, con el foco puesto', async () => {
    const usuario = userEvent.setup()
    montar()
    await llegarAlConteo(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))
    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    const nota = await screen.findByLabelText(/¿Qué pasó con esos/)
    expect(nota).toHaveFocus()
  })

  it('sin diferencia no pide ninguna nota', async () => {
    const usuario = userEvent.setup()
    montar({ alCerrar: { cuerpo: arqueo({ efectivoContado: 292700, diferencia: 0 }) } })
    await llegarAlConteo(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))
    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    expect(await screen.findByText('La caja cuadró')).toBeInTheDocument()
    expect(screen.queryByLabelText(/¿Qué pasó/)).not.toBeInTheDocument()
  })

  it('avisa que el respaldo quedó hecho', async () => {
    const usuario = userEvent.setup()
    montar()
    await llegarAlConteo(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Continuar' }))
    await usuario.click(screen.getByRole('button', { name: 'Cerrar la caja' }))

    expect(await screen.findByText('El respaldo quedó hecho')).toBeInTheDocument()
  })
})

describe('cuando el servidor no responde', () => {
  it('lo dice y ofrece reintentar', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('sin red') }))
    render(<Caja />)

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })
})
