import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  CATALOGO_VACIO, catalogoDePrueba, fetchFalso, PERMISOS_DUENA, PERMISOS_EMPLEADA, sesionDe,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Catalogo } = await import('../src/pantallas/Catalogo.jsx')
const { BuscadorDeVariante } = await import('../src/componentes/BuscadorDeVariante.jsx')
const { useCatalogo } = await import('../src/catalogo/useCatalogo.js')

/** La pantalla con su hook real: el filtrado que se prueba es el que corre en la tienda. */
function PantallaCatalogo({ alIrA }) {
  return <Catalogo catalogo={useCatalogo()} alIrA={alIrA} />
}

/**
 * El buscador alimentado con la MISMA lista que va a usar el POS: `catalogo.filas`.
 * Lo que se prueba no es el filtro por texto —eso ya tiene su prueba— sino de que
 * lista se sirve, que es la decision que se puede revertir sin que se note.
 */
function BuscadorDeVenta() {
  const catalogo = useCatalogo()
  return <BuscadorDeVariante filas={catalogo.filas} valor="" indice={0}
                             etiqueta="Producto" alElegir={() => {}} />
}

async function montar(datos = catalogoDePrueba()) {
  const espia = fetchFalso({ '/api/v1/catalogo': { cuerpo: datos } })
  vi.stubGlobal('fetch', espia)
  render(<PantallaCatalogo />)
  await screen.findByRole('heading', { name: 'Productos' })
  return espia
}

function filas() {
  const tabla = screen.queryByRole('table')
  if (!tabla) return []
  return within(tabla).queryAllByRole('row').slice(1) // sin la cabecera
}

describe('Catálogo', () => {
  beforeEach(() => {
    contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
  })

  /**
   * Un `fetch` por tecla funciona en desarrollo con tres productos y se cae en el
   * mostrador con el inventario real, justo cuando hay una clienta esperando. Y
   * como en desarrollo se ve igual de rapido, no se nota hasta que es tarde.
   */
  it('la búsqueda filtra sin llamar al servidor', async () => {
    const usuario = userEvent.setup()
    const espia = await montar()

    const llamadasIniciales = espia.mock.calls.length
    expect(filas()).toHaveLength(3)

    await usuario.type(screen.getByLabelText('Buscar'), 'nude')

    expect(filas()).toHaveLength(1)
    expect(filas()[0]).toHaveTextContent('Nude')
    // Cuatro teclas mas, cero peticiones mas.
    expect(espia.mock.calls).toHaveLength(llamadasIniciales)
  })

  it('la búsqueda ignora tildes y mayúsculas, igual que el backend', async () => {
    const usuario = userEvent.setup()
    await montar()

    await usuario.type(screen.getByLabelText('Buscar'), 'MASCARA DE PESTANAS')
    // "Máscara de pestañas" con tildes y eñe: si el front normalizara distinto del
    // backend, el buscador y el validador de duplicados discreparian.
    expect(filas()).toHaveLength(1)
    expect(filas()[0]).toHaveTextContent('9 ml')
  })

  it('el filtro de stock bajo usa el mismo criterio que el backend', async () => {
    const usuario = userEvent.setup()
    await montar()

    await usuario.click(screen.getByLabelText('Solo stock bajo'))

    // stock < stockMinimo. La de stock 0 tiene minimo 0, asi que NO esta baja:
    // agotada y bajo el minimo no son lo mismo, y bajoMinimo() del backend
    // tampoco la cuenta.
    expect(filas()).toHaveLength(1)
    expect(filas()[0]).toHaveTextContent('Nude')
  })

  /**
   * Negativo y bajo son los dos ciertos a la vez, pero solo uno es un descuadre.
   * Bajo dice "hay que reponer"; negativo dice "se vendio algo que no habia", que
   * es lo que queda despues de anular una compra cuya mercancia ya salio. Marcarlos
   * igual esconderia el segundo dentro del primero, que es cotidiano y se ignora.
   */
  it('el stock negativo se marca distinto del stock bajo', async () => {
    const datos = catalogoDePrueba()
    datos.variantes[0] = { ...datos.variantes[0], stock: -3, stockMinimo: 2 }
    await montar(datos)

    const negativa = filas().find((fila) => fila.textContent.includes('Rojo carmín'))
    expect(within(negativa).getByText('negativo')).toBeInTheDocument()
    expect(within(negativa).queryByText('bajo')).not.toBeInTheDocument()

    // Y la que solo esta baja sigue diciendo "bajo".
    const baja = filas().find((fila) => fila.textContent.includes('Nude'))
    expect(within(baja).getByText('bajo')).toBeInTheDocument()
  })

  /**
   * Una variante sin ningun movimiento es un registro sobre nada: se creo dentro de
   * un borrador de compra que todavia no llego, o de uno que se descarto. Listarla
   * ofrece vender algo que nunca entro y sin costo real, y esa venta congelaria
   * costo 0 en la VentaItem: el margen historico queda corrompido para siempre y no
   * hay pantalla donde eso se vea.
   */
  it('una variante sin historial no se lista', async () => {
    await montar()

    expect(filas()).toHaveLength(3)
    expect(screen.queryByText('Coral pendiente')).not.toBeInTheDocument()
    // Ni siquiera cuenta en el total: "3 de 4" delataria que existe.
    expect(screen.getByRole('status')).toHaveTextContent('3 de 3 variantes')
  })

  it('tampoco la sugiere el buscador, que es por donde se vendería', async () => {
    const usuario = userEvent.setup()
    vi.stubGlobal('fetch', fetchFalso({ '/api/v1/catalogo': { cuerpo: catalogoDePrueba() } }))
    render(<BuscadorDeVenta />)

    const entrada = await screen.findByRole('combobox')
    await usuario.type(entrada, 'coral')
    expect(screen.getByText('Ningún producto coincide.')).toBeInTheDocument()

    // Y con una que si tiene historial el buscador funciona: la prueba anterior
    // pasaria igual si el buscador estuviera roto del todo.
    await usuario.clear(entrada)
    await usuario.type(entrada, 'carmín')
    expect(screen.getByRole('option', { name: /Rojo carmín/ })).toBeInTheDocument()
  })

  /**
   * Desde aqui no nace ningun producto: nacen donde entra mercancia con un costo.
   * El estado vacio tiene que decir eso y llevar a los dos sitios, no dejar a
   * alguien buscando en el menu.
   */
  it('con el catálogo vacío manda a las dos pantallas por donde entra la mercancía', async () => {
    const usuario = userEvent.setup()
    const irA = vi.fn()
    vi.stubGlobal('fetch', fetchFalso({ '/api/v1/catalogo': { cuerpo: CATALOGO_VACIO } }))
    render(<PantallaCatalogo alIrA={irA} />)
    await screen.findByRole('heading', { name: 'Productos' })

    expect(screen.getByText('Todavía no hay productos con existencias')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Crear el primer producto/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()

    await usuario.click(screen.getByRole('button', { name: 'Registrar una compra' }))
    expect(irA).toHaveBeenCalledWith({ seccion: 'compras', pestana: 'compras' })

    await usuario.click(screen.getByRole('button', { name: 'Hacer la carga inicial' }))
    expect(irA).toHaveBeenCalledWith({ seccion: 'inventario', pestana: 'carga-inicial' })
  })

  /**
   * El catalogo administra lo que ya existe. Los POST siguen en el backend porque
   * los usa el flujo de compra; lo que no puede volver es la entrada desde aqui.
   */
  it('no ofrece crear productos ni variantes, ni siquiera a la DUEÑA', async () => {
    await montar()

    expect(screen.queryByRole('button', { name: /Nuevo producto/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Nueva variante/ })).not.toBeInTheDocument()
    // Editar si: los precios cambian sin que haya una compra de por medio.
    expect(screen.getAllByRole('button', { name: /^Editar/ }).length).toBeGreaterThan(0)
  })

  it('sin servidor, lo dice y ofrece reintentar', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    render(<PantallaCatalogo />)

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('un 500 no se confunde con falta de conexión', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/catalogo': { estado: 500, cuerpo: { codigo: 'ERROR_INTERNO', error: 'Algo falló.' } },
    }))
    render(<PantallaCatalogo />)

    expect(await screen.findByText('El servidor falló')).toBeInTheDocument()
    expect(screen.queryByText('No hay conexión con el servidor')).not.toBeInTheDocument()
  })

  describe('con sesión de EMPLEADA', () => {
    beforeEach(() => {
      contexto.sesion = sesionDe('EMPLEADA', PERMISOS_EMPLEADA)
    })

    /**
     * Que el front no pida los costos es la mitad de la regla; la otra la impone el
     * backend con un 403. Esta mitad se rompe agregando una llamada "por si acaso"
     * en una pantalla nueva, y no se nota porque el 403 se traga en un catch.
     */
    it('no pide costos a ningún endpoint', async () => {
      const usuario = userEvent.setup()
      const espia = await montar()

      await usuario.type(screen.getByLabelText('Buscar'), 'labial')

      const rutas = espia.mock.calls.map(([ruta]) => ruta)
      expect(rutas).not.toEqual(expect.arrayContaining([expect.stringContaining('costo')]))
      expect(rutas).toEqual(['/api/v1/catalogo'])
    })

    it('no muestra ninguna columna de costo ni de margen', async () => {
      await montar()

      const encabezados = screen.getAllByRole('columnheader').map((th) => th.textContent)
      expect(encabezados).toEqual(['Marca', 'Producto', 'Tono', 'Tamaño', 'Precio', 'Stock', ''])
      expect(screen.queryByText(/costo/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/margen/i)).not.toBeInTheDocument()
    })

    it('no ofrece editar el catálogo', async () => {
      await montar()

      expect(screen.queryByRole('button', { name: /^Editar/ })).not.toBeInTheDocument()
    })
  })
})
