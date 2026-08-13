import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { catalogoDePrueba, fetchFalso } from './ayudas.jsx'
import { CargaInicial } from '../src/pantallas/CargaInicial.jsx'
import { useCatalogo } from '../src/catalogo/useCatalogo.js'

function Pantalla() {
  return <CargaInicial catalogo={useCatalogo()} />
}

async function montar(respuestaDeCarga) {
  const espia = fetchFalso({
    '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    '/api/v1/inventario/carga-inicial': respuestaDeCarga
      ?? { estado: 201, cuerpo: { variantesCargadas: 1, movimientos: [] } },
  })
  vi.stubGlobal('fetch', espia)
  render(<Pantalla />)
  await screen.findByRole('heading', { name: 'Carga inicial de existencias' })
  return espia
}

const usuarioSinRaton = () => userEvent.setup()

async function llenarLinea(usuario, numero, { varianteId, cantidad, costo }) {
  await usuario.selectOptions(screen.getByLabelText(`Variante de la línea ${numero}`), varianteId)
  await usuario.type(screen.getByLabelText(`Cantidad de la línea ${numero}`), cantidad)
  await usuario.type(screen.getByLabelText(`Costo unitario de la línea ${numero}`), costo)
}

function envios(espia) {
  return espia.mock.calls.filter(([ruta]) => ruta === '/api/v1/inventario/carga-inicial')
}

describe('Carga inicial', () => {
  /**
   * Verificar el encadenado del foco a mano son horas, y romperlo es tan facil
   * como envolver un campo en un div con tabindex. Quien esta copiando cien
   * productos de una lista no puede alternar teclado y raton en cada uno: eso no
   * es incomodidad, son horas de trabajo.
   */
  it('el Tab encadena variante → cantidad → costo, sin nada en medio', async () => {
    const usuario = usuarioSinRaton()
    await montar()

    screen.getByLabelText('Variante de la línea 1').focus()
    await usuario.tab()
    expect(screen.getByLabelText('Cantidad de la línea 1')).toHaveFocus()
    await usuario.tab()
    expect(screen.getByLabelText('Costo unitario de la línea 1')).toHaveFocus()
  })

  it('Enter en el costo agrega línea y deja el foco en su primer campo', async () => {
    const usuario = usuarioSinRaton()
    await montar()

    await llenarLinea(usuario, 1, { varianteId: '1000', cantidad: '12', costo: '21000' })
    await usuario.keyboard('{Enter}')

    const siguiente = await screen.findByLabelText('Variante de la línea 2')
    // El foco se pide en el frame siguiente, cuando la fila ya existe en el DOM.
    await waitFor(() => expect(siguiente).toHaveFocus())
  })

  it('con varias líneas, Enter en una intermedia salta a la siguiente ya existente', async () => {
    const usuario = usuarioSinRaton()
    await montar()

    await llenarLinea(usuario, 1, { varianteId: '1000', cantidad: '2', costo: '1000' })
    await usuario.keyboard('{Enter}')
    await screen.findByLabelText('Variante de la línea 2')

    screen.getByLabelText('Costo unitario de la línea 1').focus()
    await usuario.keyboard('{Enter}')

    // No agrega una tercera: salta a la que ya hay.
    expect(screen.queryByLabelText('Variante de la línea 3')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Variante de la línea 2')).toHaveFocus())
  })

  /**
   * Un doble clic en "Cargar" mandaria el lote dos veces. La segunda revienta con
   * 409 y deja a quien lo pulso mirando un error que provoco el mismo sin saberlo,
   * despues de haber cargado bien el inventario.
   */
  it('el botón queda bloqueado mientras hay un envío en vuelo', async () => {
    const usuario = usuarioSinRaton()
    let liberar
    const enVuelo = new Promise((resolver) => { liberar = resolver })

    const espia = fetchFalso({
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
      '/api/v1/inventario/carga-inicial': () => enVuelo,
    })
    vi.stubGlobal('fetch', espia)
    render(<Pantalla />)
    await screen.findByRole('heading', { name: 'Carga inicial de existencias' })

    await llenarLinea(usuario, 1, { varianteId: '1000', cantidad: '5', costo: '9000' })

    const boton = screen.getByRole('button', { name: /Cargar 1 línea/ })
    await usuario.click(boton)

    const ocupado = await screen.findByRole('button', { name: 'Cargando…' })
    expect(ocupado).toBeDisabled()
    await usuario.click(ocupado, { pointerEventsCheck: 0 })
    expect(envios(espia)).toHaveLength(1)

    liberar({ estado: 201, cuerpo: { variantesCargadas: 1, movimientos: [] } })
    await screen.findByText('Se cargaron 1 variante(s)')
    expect(envios(espia)).toHaveLength(1)
  })

  it('el botón no permite enviar un lote sin líneas completas', async () => {
    const usuario = usuarioSinRaton()
    const espia = await montar()

    expect(screen.getByRole('button', { name: /Cargar 0 línea/ })).toBeDisabled()

    // Variante y cantidad, sin costo: sigue incompleta.
    await usuario.selectOptions(screen.getByLabelText('Variante de la línea 1'), '1000')
    await usuario.type(screen.getByLabelText('Cantidad de la línea 1'), '3')
    expect(screen.getByRole('button', { name: /Cargar 0 línea/ })).toBeDisabled()
    expect(envios(espia)).toHaveLength(0)
  })

  /**
   * El backend es todo-o-nada y nombra la variante que rompio el lote. Si la
   * pantalla mostrara solo un aviso general, quien tiene cuarenta lineas cargadas
   * tendria que adivinar cual arreglar — o volver a teclearlas todas.
   */
  it('un 409 marca la línea culpable y no borra el trabajo', async () => {
    const usuario = usuarioSinRaton()
    await montar({
      estado: 409,
      cuerpo: {
        codigo: 'CARGA_INICIAL_YA_REGISTRADA',
        error: 'La variante 1001 ya tiene carga inicial. Lo que venga después va por ajuste '
          + 'de inventario, que deja constancia del motivo.',
      },
    })

    await llenarLinea(usuario, 1, { varianteId: '1000', cantidad: '4', costo: '8000' })
    await usuario.keyboard('{Enter}')
    await screen.findByLabelText('Variante de la línea 2')
    await llenarLinea(usuario, 2, { varianteId: '1001', cantidad: '6', costo: '7000' })

    await usuario.click(screen.getByRole('button', { name: /Cargar 2 línea/ }))

    // El mensaje del backend, tal cual, y en la linea que lo causo.
    const marcadas = await screen.findAllByRole('alert')
    expect(marcadas).toHaveLength(1)
    expect(marcadas[0]).toHaveTextContent('La variante 1001 ya tiene carga inicial.')

    // Y las dos lineas siguen ahi con sus valores.
    expect(screen.getByLabelText('Cantidad de la línea 1')).toHaveValue('4')
    expect(screen.getByLabelText('Cantidad de la línea 2')).toHaveValue('6')
  })

  it('los campos numéricos no aceptan letras ni puntos de miles', async () => {
    const usuario = usuarioSinRaton()
    await montar()

    // Todo monto es un entero de pesos. Un "21.000" tecleado por costumbre se
    // convertiria en veintiun pesos si el front lo dejara pasar tal cual.
    await usuario.type(screen.getByLabelText('Costo unitario de la línea 1'), '21.000abc')
    expect(screen.getByLabelText('Costo unitario de la línea 1')).toHaveValue('21000')
  })
})
