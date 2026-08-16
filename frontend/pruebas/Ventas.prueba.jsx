import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  fetchFalso, PERMISOS_DUENA, PERMISOS_EMPLEADA, resumenDeVenta, sesionDe,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Ventas } = await import('../src/pantallas/Ventas.jsx')

const DEL_DIA = [
  resumenDeVenta({ id: 500, consecutivo: 'V-000123', fecha: '2026-08-15T14:32:00',
    total: 102300, metodoPago: 'EFECTIVO' }),
  resumenDeVenta({ id: 501, consecutivo: 'V-000124', fecha: '2026-08-15T15:05:00',
    total: 24500, metodoPago: 'NEQUI' }),
  resumenDeVenta({ id: 502, consecutivo: 'V-000125', fecha: '2026-08-15T15:40:00',
    total: 52000, metodoPago: 'EFECTIVO', estado: 'ANULADA',
    motivoAnulacion: 'cobro mal hecho' }),
]

async function montar({ ventas = DEL_DIA, alAnular } = {}) {
  const pedidas = []

  const espia = fetchFalso({
    '/api/v1/ventas': (ruta, opciones) => {
      if (opciones?.method === 'POST') return alAnular ?? { cuerpo: {} }
      pedidas.push(ruta)
      return { cuerpo: ventas }
    },
  })

  vi.stubGlobal('fetch', espia)
  render(<Ventas />)
  await screen.findByRole('heading', { name: 'Ventas' })
  return { espia, pedidas }
}

const filas = () => {
  const tabla = screen.queryByRole('table')
  return tabla ? within(tabla).queryAllByRole('row').slice(1) : []
}

beforeEach(() => {
  contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
})

describe('el listado del día', () => {
  it('muestra consecutivo, hora, total, método y estado', async () => {
    await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    const primera = within(filas()[0])
    expect(primera.getByText('V-000123')).toBeInTheDocument()
    expect(primera.getByText('14:32')).toBeInTheDocument()
    expect(primera.getByText('102.300')).toBeInTheDocument()
    expect(primera.getByText('Efectivo')).toBeInTheDocument()
    expect(primera.getByText('completada')).toBeInTheDocument()
  })

  /**
   * La anulada NO desaparece: la venta existió, se cobró y se deshizo, y el listado es
   * donde queda constancia de las tres cosas junto con el motivo.
   */
  it('la venta anulada sigue en la lista, con su motivo', async () => {
    await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    const anulada = within(filas()[2])
    expect(anulada.getByText('anulada')).toBeInTheDocument()
    expect(anulada.getByText('cobro mal hecho')).toBeInTheDocument()
    // Y no ofrece anularla otra vez: sería devolver el inventario dos veces.
    expect(anulada.queryByRole('button', { name: 'Anular' })).not.toBeInTheDocument()
  })

  /** Ni costos ni márgenes, como el resto del módulo. */
  it('no pide ni muestra costos', async () => {
    const { espia } = await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    expect(document.body.textContent.toLowerCase()).not.toContain('costo')
    expect(document.body.textContent.toLowerCase()).not.toContain('margen')
    expect(espia.mock.calls.some(([ruta]) => ruta.includes('costos'))).toBe(false)
  })

  it('cambiar el día vuelve a pedir con esa fecha', async () => {
    const usuario = userEvent.setup()
    const { pedidas } = await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    // fireEvent y no type(): en un <input type="date"> jsdom no simula el tecleo del
    // selector nativo, y lo que se prueba es que cambiar el dia vuelve a pedir.
    fireEvent.change(screen.getByLabelText('Día'), { target: { value: '2026-08-01' } })

    await waitFor(() => expect(pedidas.at(-1)).toContain('fecha=2026-08-01'))
  })

  it('dice que no hubo ventas cuando el día está vacío', async () => {
    await montar({ ventas: [] })
    expect(await screen.findByText('No hubo ventas ese día.')).toBeInTheDocument()
  })
})

describe('anular', () => {
  /**
   * Solo la DUENA. Esconder el botón no protege nada —el interceptor del backend
   * responde 403— pero evita ofrecerle a la EMPLEADA una puerta que se le va a cerrar
   * en la cara.
   */
  it('la EMPLEADA no ve el botón de anular', async () => {
    contexto.sesion = sesionDe('EMPLEADA', PERMISOS_EMPLEADA)
    await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    expect(screen.queryByRole('button', { name: 'Anular' })).not.toBeInTheDocument()
  })

  /**
   * LA CONFIRMACION DICE LAS DOS CONSECUENCIAS, y la segunda sorprende: la plata sale
   * de la caja de HOY, no de la del día en que se cobró. Sobre una venta de la semana
   * pasada eso es un movimiento en el arqueo de esta noche, y quien anula tiene que
   * saberlo antes de confirmar, no descubrirlo cuadrando.
   */
  it('advierte del inventario y de la plata de la caja de hoy', async () => {
    const usuario = userEvent.setup()
    await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    await usuario.click(within(filas()[0]).getByRole('button', { name: 'Anular' }))

    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/vuelven al inventario/)).toBeInTheDocument()
    expect(dialogo.getByText(/102.300 de la caja de hoy/)).toBeInTheDocument()
  })

  /** Una venta que no fue en efectivo no saca nada del cajón, y no lo dice. */
  it('no habla de la caja cuando no se pagó en efectivo', async () => {
    const usuario = userEvent.setup()
    await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    await usuario.click(within(filas()[1]).getByRole('button', { name: 'Anular' }))

    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/vuelven al inventario/)).toBeInTheDocument()
    expect(dialogo.queryByText(/de la caja de hoy/)).not.toBeInTheDocument()
  })

  /**
   * El motivo es obligatorio. Sin él, una venta que desapareció del inventario y del
   * cajón es una pregunta que nadie va a poder responder dentro de seis meses.
   */
  it('no deja anular sin motivo', async () => {
    const usuario = userEvent.setup()
    const { espia } = await montar()
    await waitFor(() => expect(filas()).toHaveLength(3))

    await usuario.click(within(filas()[0]).getByRole('button', { name: 'Anular' }))
    expect(screen.getByRole('button', { name: 'Anular la venta' })).toBeDisabled()

    await usuario.type(screen.getByLabelText(/¿Por qué/), 'se equivocó de tono')
    await usuario.click(screen.getByRole('button', { name: 'Anular la venta' }))

    await waitFor(() => {
      const anulacion = espia.mock.calls.find(([ruta]) => ruta.includes('/anulacion'))
      expect(JSON.parse(anulacion[1].body)).toEqual({ motivo: 'se equivocó de tono' })
    })
  })
})
