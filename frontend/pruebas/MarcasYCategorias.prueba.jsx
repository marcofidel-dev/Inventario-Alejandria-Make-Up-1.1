import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { catalogoDePrueba, fetchFalso } from './ayudas.jsx'
import { MarcasYCategorias } from '../src/pantallas/MarcasYCategorias.jsx'
import { useCatalogo } from '../src/catalogo/useCatalogo.js'

function Pantalla() {
  return <MarcasYCategorias catalogo={useCatalogo()} />
}

async function montar({ catalogo = catalogoDePrueba(), alRenombrar } = {}) {
  const espia = fetchFalso({
    '/api/v1/catalogo/marcas': alRenombrar ?? { cuerpo: { id: 1, nombre: 'L’Oréal Paris', activo: true } },
    '/api/v1/catalogo/categorias': { cuerpo: { id: 10, nombre: 'Labios', activo: true } },
    '/api/v1/catalogo': { cuerpo: catalogo },
  })
  // Renombrar, desactivar y reactivar cuelgan del mismo prefijo de ruta, y
  // fetchFalso resuelve por prefijo: los tres pasan por el mismo manejador y se
  // distinguen despues por metodo y sufijo.
  vi.stubGlobal('fetch', espia)
  render(<Pantalla />)
  await screen.findByRole('heading', { name: 'Marcas y categorías' })
  return espia
}

/**
 * La fila, re-consultada cada vez. Cualquier accion recarga el catalogo y mientras
 * dura la recarga la pantalla entera se reemplaza por "Cargando…": una referencia
 * guardada de antes apunta a un nodo que ya no esta en el documento, y pulsarlo no
 * hace nada sin que la prueba se entere.
 */
async function filaDe(titulo, nombre) {
  const seccion = (await screen.findByRole('heading', { name: titulo })).closest('section')
  return within(within(seccion).getByText(nombre).closest('tr'))
}

const tablaDe = async (titulo) => {
  const seccion = (await screen.findByRole('heading', { name: titulo })).closest('section')
  return within(seccion)
}

describe('Marcas y categorías', () => {
  /**
   * LA PANTALLA ADMINISTRA, NO CREA. Es la misma regla que el catalogo: una marca
   * nace cuando se usa —dentro de una compra o de la carga inicial— y no antes. Una
   * marca creada "por si acaso" no la usa nadie y no la borra nadie.
   */
  it('no ofrece crear ninguna marca ni ninguna categoría', async () => {
    await montar()

    expect(screen.queryByRole('button', { name: /Nueva marca/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Nueva categoría/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Crear/ })).not.toBeInTheDocument()
  })

  /**
   * El conteo sale de los productos ya cargados, sin pedirle nada al servidor: es lo
   * que dice si desactivar una marca es inofensivo o si deja media vitrina huerfana.
   */
  it('cuenta los productos de cada una y marca las que no tienen ninguno', async () => {
    const catalogo = catalogoDePrueba()
    catalogo.marcas.push({ id: 3, nombre: 'Essence', activo: true })
    const espia = await montar({ catalogo })

    const marcas = await tablaDe('Marcas')
    // Loréal tiene el labial; Maybelline la máscara; Essence ninguno.
    const fila = (nombre) => marcas.getByText(nombre).closest('tr')
    expect(within(fila('Loréal')).getByText('1')).toBeInTheDocument()
    expect(within(fila('Essence')).getByText('sin productos')).toBeInTheDocument()
    expect(within(fila('Loréal')).queryByText('sin productos')).not.toBeInTheDocument()

    // Ni una llamada mas que la del catalogo completo.
    expect(espia.mock.calls.map(([ruta]) => ruta)).toEqual(['/api/v1/catalogo'])
  })

  it('renombrar manda el nombre nuevo y recarga el catálogo', async () => {
    const usuario = userEvent.setup()
    let cuerpo = null
    const espia = await montar({
      alRenombrar: (ruta, opciones) => {
        if (opciones?.method === 'PUT') cuerpo = JSON.parse(opciones.body)
        return { cuerpo: { id: 1, nombre: 'L’Oréal Paris', activo: true } }
      },
    })

    await usuario.click((await filaDe('Marcas', 'Loréal')).getByRole('button', { name: 'Renombrar' }))

    const campo = await screen.findByLabelText('Nombre')
    await usuario.clear(campo)
    await usuario.type(campo, 'L’Oréal Paris{Enter}')

    expect(cuerpo).toEqual({ nombre: 'L’Oréal Paris' })
    // Y el catalogo se vuelve a pedir: el nombre nuevo tiene que llegar a las otras
    // pantallas, que lo tienen cargado en memoria.
    await screen.findByRole('heading', { name: 'Marcas y categorías' })
    expect(espia.mock.calls.filter(([ruta, o]) => ruta === '/api/v1/catalogo' && o?.method === 'GET'))
      .toHaveLength(2)
  })

  /**
   * EL MENSAJE DEL 409 SE MUESTRA TAL CUAL, porque nombra la marca con la que se
   * choca. "Ya existe la marca «Loréal»" es lo que hace falta saber para decidir si
   * lo que sobra es esta o la otra; "nombre duplicado" no lo es.
   */
  it('si el nombre choca, se muestra el mensaje del backend sin reescribirlo', async () => {
    const usuario = userEvent.setup()
    await montar({
      alRenombrar: (ruta, opciones) => (opciones?.method === 'PUT'
        ? { estado: 409, cuerpo: { codigo: 'NOMBRE_DUPLICADO', error: 'Ya existe la marca "Maybelline", que es el mismo nombre salvo tildes o mayúsculas.' } }
        : { cuerpo: {} }),
    })

    const marcas = await tablaDe('Marcas')
    const fila = marcas.getByText('Loréal').closest('tr')
    await usuario.click(within(fila).getByRole('button', { name: 'Renombrar' }))

    const campo = await screen.findByLabelText('Nombre')
    await usuario.clear(campo)
    await usuario.type(campo, 'maybelline{Enter}')

    expect(await screen.findByText(/Ya existe la marca "Maybelline"/)).toBeInTheDocument()
  })

  /** Desactivar es POST a .../desactivacion. Nunca DELETE: nada se borra. */
  it('desactivar y reactivar pasan por el endpoint de activación, no por un borrado', async () => {
    const usuario = userEvent.setup()
    const catalogo = catalogoDePrueba()
    catalogo.categorias[1].activo = false
    const espia = await montar({ catalogo })

    await usuario.click(
      (await filaDe('Categorías', 'Labios')).getByRole('button', { name: 'Desactivar' }),
    )
    await usuario.click(
      (await filaDe('Categorías', 'Ojos')).getByRole('button', { name: 'Reactivar' }),
    )

    const escrituras = espia.mock.calls.filter(([, o]) => o?.method && o.method !== 'GET')
    expect(escrituras.map(([ruta, o]) => `${o.method} ${ruta}`)).toEqual([
      'POST /api/v1/catalogo/categorias/10/desactivacion',
      'POST /api/v1/catalogo/categorias/11/reactivacion',
    ])
  })

  /**
   * FUSIONAR ES UNA DECISION PENDIENTE, no una funcionalidad a medias. La prueba
   * existe para que agregarla sea una decision consciente y no un descuido: el dia
   * que se construya, esta prueba se borra a mano.
   */
  it('todavía no ofrece fusionar duplicadas', async () => {
    await montar()

    expect(screen.queryByRole('button', { name: /Fusionar/i })).not.toBeInTheDocument()
  })
})
