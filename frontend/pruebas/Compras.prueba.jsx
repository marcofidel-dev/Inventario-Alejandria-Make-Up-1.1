import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  catalogoDePrueba, comprasDePrueba, fetchFalso, PERMISOS_DUENA, proveedoresDePrueba, sesionDe,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Compras } = await import('../src/pantallas/Compras.jsx')
const { useCatalogo } = await import('../src/catalogo/useCatalogo.js')
const { useCompras } = await import('../src/compras/useCompras.js')

/** La pantalla con sus hooks reales: lo que se prueba es lo que corre en la tienda. */
function PantallaCompras() {
  return <Compras catalogo={useCatalogo()} compras={useCompras()} />
}

async function montar({ compras = comprasDePrueba(), proveedores = proveedoresDePrueba(),
  alCrearCompra } = {}) {
  const espia = fetchFalso({
    '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    '/api/v1/proveedores': { cuerpo: proveedores },
    // La misma ruta sirve al listado y a la creacion: se distinguen por el metodo.
    '/api/v1/compras': (ruta, opciones) => (
      opciones?.method === 'POST' ? alCrearCompra(ruta, opciones) : { cuerpo: compras }
    ),
  })
  vi.stubGlobal('fetch', espia)
  render(<PantallaCompras />)
  await screen.findByRole('heading', { name: 'Compras' })
  return espia
}

const filas = () => {
  const tabla = screen.queryByRole('table')
  return tabla ? within(tabla).queryAllByRole('row').slice(1) : []
}

describe('Compras', () => {
  beforeEach(() => {
    contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
  })

  /**
   * LOS BORRADORES PRIMERO. Son lo unico de la lista que exige hacer algo hoy:
   * mercancia que puede estar en el piso sin registrar. El resto es historia, y da
   * igual si tiene tres dias o tres meses.
   */
  it('pone los borradores primero, aunque no sean los más recientes', async () => {
    await montar()

    // El fixture trae la anulada como la mas reciente y el borrador en el medio.
    expect(filas()[0]).toHaveTextContent('C-000901')
    expect(filas()[0]).toHaveTextContent('BORRADOR')
  })

  it('cuenta los borradores pendientes a la vista', async () => {
    await montar()

    expect(screen.getByText('1 compra(s) en borrador')).toBeInTheDocument()
  })

  /** Sin borradores no hay nada que reclamar, y el aviso no aparece. */
  it('sin borradores no muestra el aviso', async () => {
    await montar({ compras: comprasDePrueba().filter((c) => c.estado !== 'BORRADOR') })

    // Se mira el aviso, no cualquier "borrador": el desplegable de estados tiene
    // una opcion con ese nombre y siempre esta.
    expect(screen.queryByText(/compra\(s\) en borrador/)).not.toBeInTheDocument()
  })

  /**
   * DESCARTAR Y ANULAR NUNCA JUNTOS: descartar solo existe sobre un borrador
   * —no hubo mercancia, no se revierte nada— y anular solo sobre una recibida, que
   * devuelve stock. Ofrecer los dos a la vez seria invitar a elegir mal.
   */
  it('descartar solo aparece en borrador y anular solo en recibida', async () => {
    await montar()

    const borrador = filas().find((fila) => fila.textContent.includes('C-000901'))
    const recibida = filas().find((fila) => fila.textContent.includes('C-000900'))

    expect(within(borrador).getByRole('button', { name: 'Descartar' })).toBeInTheDocument()
    expect(within(borrador).queryByRole('button', { name: 'Anular' })).not.toBeInTheDocument()

    expect(within(recibida).getByRole('button', { name: 'Anular' })).toBeInTheDocument()
    expect(within(recibida).queryByRole('button', { name: 'Descartar' })).not.toBeInTheDocument()
  })

  /** Una compra sin lineas no mueve inventario, y el boton lo dice en vez de fallar. */
  it('una compra sin líneas no se puede recibir y el botón lo dice', async () => {
    const sinLineas = [{ ...comprasDePrueba()[1], id: 903, consecutivo: 'C-000903', items: [] }]
    await montar({ compras: sinLineas })

    const boton = screen.getByRole('button', { name: 'Sin líneas que recibir' })
    expect(boton).toBeDisabled()
  })

  it('sin proveedores, lo primero es crear uno', async () => {
    await montar({ proveedores: [] })

    expect(screen.getByText('Primero hace falta un proveedor')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Registrar una compra/ })).toBeDisabled()
  })

  describe('registrar una compra', () => {
    /**
     * EL TOTAL QUE VALE ES EL DEL SERVIDOR.
     *
     * El servidor devuelve a proposito un total distinto del que la pantalla venia
     * sumando. Si la pantalla mostrara el suyo, este test no lo notaria nunca con un
     * fixture "coherente": por eso el fixture miente. Lo que se afirma es que el
     * numero que queda en pantalla es el que quedo guardado, no el que se calculo
     * mientras se tecleaba.
     */
    it('al guardar muestra el total que devolvió el servidor, no el suyo', async () => {
      const usuario = userEvent.setup()
      let cuerpoEnviado = null

      await montar({
        alCrearCompra: (ruta, opciones) => {
          cuerpoEnviado = JSON.parse(opciones.body)
          return { estado: 201, cuerpo: { ...comprasDePrueba()[1], total: 999999 } }
        },
      })

      await usuario.click(screen.getByRole('button', { name: /Registrar una compra/ }))
      await screen.findByRole('heading', { name: 'Registrar una compra' })

      await usuario.selectOptions(screen.getByLabelText('Proveedor'), '50')

      await usuario.type(screen.getByLabelText('Variante de la línea 1'), 'nude')
      await usuario.click(await screen.findByRole('option', { name: /Nude/ }))

      await usuario.type(screen.getByLabelText('Cantidad de la línea 1'), '3')
      await usuario.type(screen.getByLabelText('Costo unitario de la línea 1'), '12000')

      // Lo que la pantalla venia sumando: 3 x 12.000. Se mira el total y no el
      // subtotal de la linea, que tambien dice 36.000.
      const orientativo = screen.getByText('Total aproximado').parentElement
      expect(within(orientativo).getByText('36.000')).toBeInTheDocument()

      await usuario.click(screen.getByRole('button', { name: 'Guardar borrador' }))

      const total = await screen.findByText('999.999')
      expect(total).toBeInTheDocument()
      expect(screen.queryByText('36.000')).not.toBeInTheDocument()

      // Y el total no viaja en la peticion: no hay campo donde mandarlo.
      expect(cuerpoEnviado).not.toHaveProperty('total')
      expect(cuerpoEnviado.lineas).toEqual([
        { varianteId: 1001, cantidad: 3, costoUnitario: 12000 },
      ])
    })

    /**
     * La busqueda de variante filtra sobre el catalogo que ya esta en memoria. Una
     * llamada por tecla funciona con tres productos y se cae con el inventario real,
     * justo cuando hay una factura de cuarenta renglones esperando.
     */
    it('buscar la variante no llama al servidor', async () => {
      const usuario = userEvent.setup()
      const espia = await montar()

      await usuario.click(screen.getByRole('button', { name: /Registrar una compra/ }))
      await screen.findByRole('heading', { name: 'Registrar una compra' })

      const llamadasAntes = espia.mock.calls.length
      await usuario.type(screen.getByLabelText('Variante de la línea 1'), 'labial')

      expect(await screen.findAllByRole('option')).not.toHaveLength(0)
      expect(espia.mock.calls).toHaveLength(llamadasAntes)
    })

    /**
     * LA EXCEPCION A LA REGLA DEL CATALOGO, y por eso tiene prueba propia.
     *
     * El catalogo y el buscador de venta esconden las variantes sin historial. Aqui
     * no: la mercancia esta llegando, la variante se acaba de crear para esta misma
     * factura y todavia no tiene ni un movimiento. Si alguien "corrige" esta pantalla
     * para que use la lista corta, comprar un producto nuevo deja de ser posible y no
     * se nota hasta que hay una factura de proveedor sobre el mostrador.
     */
    it('el buscador de la compra sí encuentra una variante sin historial', async () => {
      const usuario = userEvent.setup()
      await montar()

      await usuario.click(screen.getByRole('button', { name: /Registrar una compra/ }))
      await screen.findByRole('heading', { name: 'Registrar una compra' })

      await usuario.type(screen.getByLabelText('Variante de la línea 1'), 'coral')

      expect(await screen.findByRole('option', { name: /Coral pendiente/ })).toBeInTheDocument()
    })

    /** Enter en el costo agrega la linea siguiente: la factura se copia sin raton. */
    it('Enter en el costo agrega otra línea', async () => {
      const usuario = userEvent.setup()
      await montar()

      await usuario.click(screen.getByRole('button', { name: /Registrar una compra/ }))
      await screen.findByRole('heading', { name: 'Registrar una compra' })

      expect(screen.queryByLabelText('Variante de la línea 2')).not.toBeInTheDocument()

      await usuario.type(screen.getByLabelText('Costo unitario de la línea 1'), '5000{Enter}')

      expect(screen.getByLabelText('Variante de la línea 2')).toBeInTheDocument()
    })
  })
})
