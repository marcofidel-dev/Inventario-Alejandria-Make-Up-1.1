import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { catalogoDePrueba, fetchFalso } from './ayudas.jsx'
import { FormularioProducto } from '../src/pantallas/FormularioProducto.jsx'
import { useCatalogo } from '../src/catalogo/useCatalogo.js'

/**
 * Como se muestran los errores del backend. Es el contrato entre las dos mitades
 * del sistema: el front ramifica sobre `codigo` y muestra el `error` tal cual,
 * porque el mensaje ya esta escrito para leerse en pantalla y nombra el registro
 * con el que se choca. Si el front ramificara sobre el texto, cualquier ajuste de
 * redaccion en el backend movería el error al sitio equivocado sin que nada falle.
 */

function Pantalla({ alGuardar = vi.fn() }) {
  return <FormularioProducto catalogo={useCatalogo()} alCerrar={() => {}} alGuardar={alGuardar} />
}

async function montar(respuestaAlCrear, alGuardar) {
  const espia = fetchFalso({
    '/api/v1/catalogo/productos': respuestaAlCrear ?? { estado: 201, cuerpo: { id: 500 } },
    '/api/v1/catalogo/marcas': { estado: 201, cuerpo: { id: 3, nombre: 'Essence', activo: true } },
    '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
  })
  vi.stubGlobal('fetch', espia)
  render(<Pantalla alGuardar={alGuardar} />)
  await esperarElCatalogo()
  return espia
}

/** El desplegable no sirve hasta que llegan las marcas. */
async function esperarElCatalogo() {
  await screen.findByRole('option', { name: 'Loréal' })
}

async function llenarProducto(usuario, nombre = 'Labial cremoso') {
  await usuario.type(screen.getByLabelText('Nombre del producto'), nombre)
  await usuario.selectOptions(screen.getByLabelText('Marca'), '1')
  await usuario.selectOptions(screen.getByLabelText('Categoría'), '10')
}

function envios(espia) {
  return espia.mock.calls.filter(([ruta, opciones]) =>
    ruta === '/api/v1/catalogo/productos' && opciones?.method === 'POST')
}

function errorDelCampo(etiqueta) {
  const campo = screen.getByLabelText(etiqueta).closest('.campo')
  return within(campo).queryByRole('alert')?.textContent
}

describe('Formulario de producto', () => {
  it('NOMBRE_DUPLICADO se muestra debajo del nombre, que es el campo a cambiar', async () => {
    const usuario = userEvent.setup()
    await montar({
      estado: 409,
      cuerpo: {
        codigo: 'NOMBRE_DUPLICADO',
        error: 'Ya existe el producto «Labial Mate» en la marca Loréal.',
      },
    })

    await llenarProducto(usuario, 'labial mate')
    await usuario.click(screen.getByRole('button', { name: 'Crear producto' }))

    await waitFor(() => {
      expect(errorDelCampo('Nombre del producto'))
        .toBe('Ya existe el producto «Labial Mate» en la marca Loréal.')
    })
    // Y no repetido tambien como aviso general: el mismo error dos veces se lee
    // como dos problemas.
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('VALIDACION_FALLIDA reparte cada detalle bajo su campo', async () => {
    const usuario = userEvent.setup()
    await montar({
      estado: 400,
      cuerpo: {
        codigo: 'VALIDACION_FALLIDA',
        error: 'Hay datos inválidos.',
        detalles: [
          'nombre: no puede estar vacío',
          'descripcion: no puede pasar de 500 caracteres',
        ],
      },
    })

    await llenarProducto(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Crear producto' }))

    await waitFor(() => {
      expect(errorDelCampo('Nombre del producto')).toBe('no puede estar vacío')
    })
    expect(errorDelCampo('Descripción (opcional)')).toBe('no puede pasar de 500 caracteres')
    // Un monton de detalles juntos donde nadie sabe cual es de que campo no es
    // mostrar el error: es mostrar que hubo uno.
    expect(screen.queryByText('Hay datos inválidos.')).not.toBeInTheDocument()
  })

  it('UNICIDAD_VIOLADA, que no es de un campo concreto, sale como aviso del formulario', async () => {
    const usuario = userEvent.setup()
    await montar({
      estado: 409,
      cuerpo: {
        codigo: 'UNICIDAD_VIOLADA',
        error: 'Ese producto ya existe con otro nombre equivalente.',
      },
    })

    await llenarProducto(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Crear producto' }))

    expect(await screen.findByText('Ese producto ya existe con otro nombre equivalente.'))
      .toBeInTheDocument()
    expect(errorDelCampo('Nombre del producto')).toBeUndefined()
  })

  it('un fallo de red no se muestra como si el dato estuviera mal', async () => {
    const usuario = userEvent.setup()
    const espia = fetchFalso({ '/api/v1/catalogo': { cuerpo: catalogoDePrueba() } })
    vi.stubGlobal('fetch', vi.fn(async (ruta, opciones) => {
      if (opciones?.method === 'POST') throw new TypeError('Failed to fetch')
      return espia(ruta, opciones)
    }))

    render(<Pantalla />)
    await esperarElCatalogo()
    await llenarProducto(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Crear producto' }))

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    expect(errorDelCampo('Nombre del producto')).toBeUndefined()
  })

  /**
   * Dos clics en "Crear producto" crean dos productos, y el segundo pasa: los
   * nombres chocarian solo si el indice los considera iguales, y en el momento del
   * primer envio todavia no existe ninguno. Queda un duplicado real en el
   * catalogo, no un error visible.
   */
  it('el botón queda bloqueado mientras guarda, así que no crea dos productos', async () => {
    const usuario = userEvent.setup()
    let liberar
    const enVuelo = new Promise((resolver) => { liberar = resolver })

    const espia = fetchFalso({
      '/api/v1/catalogo/productos': () => enVuelo,
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    })
    vi.stubGlobal('fetch', espia)
    render(<Pantalla />)
    await esperarElCatalogo()

    await llenarProducto(usuario)
    await usuario.click(screen.getByRole('button', { name: 'Crear producto' }))

    const ocupado = await screen.findByRole('button', { name: 'Guardando…' })
    expect(ocupado).toBeDisabled()
    await usuario.click(ocupado, { pointerEventsCheck: 0 })
    expect(envios(espia)).toHaveLength(1)

    liberar({ estado: 201, cuerpo: { id: 500 } })
    await waitFor(() => expect(envios(espia)).toHaveLength(1))
  })

  it('no deja crear sin marca ni categoría, que el backend exige', async () => {
    const usuario = userEvent.setup()
    const espia = await montar()

    await usuario.type(screen.getByLabelText('Nombre del producto'), 'Labial cremoso')
    expect(screen.getByRole('button', { name: 'Crear producto' })).toBeDisabled()

    await usuario.selectOptions(screen.getByLabelText('Marca'), '1')
    expect(screen.getByRole('button', { name: 'Crear producto' })).toBeDisabled()

    await usuario.selectOptions(screen.getByLabelText('Categoría'), '10')
    expect(screen.getByRole('button', { name: 'Crear producto' })).toBeEnabled()
    expect(envios(espia)).toHaveLength(0)
  })

  /**
   * La marca se elige de la lista. Crear una nueva desde aqui es la valvula que
   * hace soportable no tener texto libre: sin ella, quien esta cargando productos
   * tendria que salir a otra pantalla y volver.
   */
  it('permite crear una marca nueva en el sitio y la deja seleccionada', async () => {
    const usuario = userEvent.setup()

    // El GET posterior refleja el POST, como haria el servidor. Con un catalogo
    // fijo la prueba pasaria o fallaria por el fixture y no por la pantalla: el
    // orden que importa es recargar primero y seleccionar despues, porque
    // seleccionar un id que todavia no esta entre las opciones lo descarta en
    // silencio y el campo se queda vacio.
    const nueva = { id: 3, nombre: 'Essence', activo: true }
    let creada = false
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/catalogo/marcas': () => { creada = true; return { estado: 201, cuerpo: nueva } },
      '/api/v1/catalogo': () => ({
        cuerpo: creada
          ? catalogoDePrueba({ marcas: [...catalogoDePrueba().marcas, nueva] })
          : catalogoDePrueba(),
      }),
    }))
    render(<Pantalla />)
    await esperarElCatalogo()

    await usuario.click(screen.getByRole('button', { name: /Nueva marca/ }))
    await usuario.type(screen.getByLabelText('Marca — nueva'), 'Essence')
    await usuario.click(screen.getByRole('button', { name: 'Crear' }))

    await waitFor(() => expect(screen.getByLabelText('Marca')).toHaveValue('3'))
    expect(screen.getByRole('option', { name: 'Essence' })).toBeInTheDocument()
  })

  it('si la marca nueva choca, el mensaje del backend se muestra sin reescribirlo', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/catalogo/marcas': {
        estado: 409,
        cuerpo: { codigo: 'NOMBRE_DUPLICADO', error: 'Ya existe la marca «Loréal».' },
      },
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    }))
    render(<Pantalla />)
    await esperarElCatalogo()

    await usuario.click(screen.getByRole('button', { name: /Nueva marca/ }))
    await usuario.type(screen.getByLabelText('Marca — nueva'), 'loreal')
    await usuario.click(screen.getByRole('button', { name: 'Crear' }))

    // Nombra el registro con el que choca. Un "ya existe" a secas dejaria a quien
    // lo lee buscando a mano cual es.
    expect(await screen.findByText('Ya existe la marca «Loréal».')).toBeInTheDocument()
  })
})
