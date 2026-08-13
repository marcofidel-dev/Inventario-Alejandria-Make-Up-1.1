import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  catalogoDePrueba, compra, comprasDePrueba, fetchFalso, PERMISOS_DUENA, proveedoresDePrueba,
  sesionDe,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { RecibirCompra } = await import('../src/pantallas/RecibirCompra.jsx')
const { AnularCompra } = await import('../src/pantallas/BajasDeCompra.jsx')
const { useCatalogo } = await import('../src/catalogo/useCatalogo.js')
const { useCompras } = await import('../src/compras/useCompras.js')

const COMPRA = compra({ id: 901, consecutivo: 'C-000901', total: 60000 })

/** Una previa con una linea sana y otra con el margen por debajo del minimo. */
function previaDePrueba(ajustes = {}) {
  return {
    compraId: 901,
    consecutivo: 'C-000901',
    total: 60000,
    hayAdvertencias: true,
    lineas: [
      {
        varianteId: 1000, cantidad: 5, stockActual: 7, stockResultante: 12,
        costoUnitario: 10000, costoPromedioActual: 8000, costoPromedioResultante: 9000,
        precioVenta: 32000, margenPorcentaje: 72,
        costoSuperaPrecio: false, margenBajo: false,
      },
      {
        varianteId: 1001, cantidad: 2, stockActual: 1, stockResultante: 3,
        costoUnitario: 28000, costoPromedioActual: 20000, costoPromedioResultante: 27000,
        precioVenta: 32000, margenPorcentaje: 16,
        costoSuperaPrecio: false, margenBajo: true,
      },
    ],
    ...ajustes,
  }
}

function Pantalla({ compra: laCompra = COMPRA }) {
  return (
    <RecibirCompra
      compra={laCompra}
      catalogo={useCatalogo()}
      compras={useCompras()}
      alCerrar={() => {}}
    />
  )
}

async function montar({ previa = previaDePrueba(), sesionDeCaja, alRecibir, alRegistrarGasto } = {}) {
  // Las rutas mas especificas van primero: fetchFalso resuelve por startsWith y se
  // queda con la primera que encaja.
  const espia = fetchFalso({
    '/api/v1/compras/901/previa-recepcion': { cuerpo: previa },
    '/api/v1/compras/901/recepcion': alRecibir
      ?? { cuerpo: { ...COMPRA, estado: 'RECIBIDA', total: 60000 } },
    '/api/v1/caja/sesiones/actual': sesionDeCaja
      ? { cuerpo: sesionDeCaja }
      : { estado: 404, cuerpo: { codigo: 'NO_ENCONTRADO', error: 'No hay ninguna sesión.' } },
    '/api/v1/caja/movimientos': alRegistrarGasto ?? { estado: 201, cuerpo: { id: 1 } },
    '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    '/api/v1/proveedores': { cuerpo: proveedoresDePrueba() },
    '/api/v1/compras': { cuerpo: comprasDePrueba() },
  })
  vi.stubGlobal('fetch', espia)
  render(<Pantalla />)
  await screen.findByRole('heading', { name: /Recibir la compra/ })
  return espia
}

describe('Recibir una compra', () => {
  beforeEach(() => {
    contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
  })

  it('muestra el costo promedio actual y el que quedaría', async () => {
    await montar()

    const tabla = screen.getByRole('table')
    expect(within(tabla).getByText('8.000')).toBeInTheDocument()
    expect(within(tabla).getByText('9.000')).toBeInTheDocument()
  })

  /**
   * La advertencia sale del servidor, no de una cuenta hecha aqui: el umbral vive en
   * el backend y la pantalla solo pinta la bandera. Si el front recalculara el
   * margen, un dia el aviso y la regla dirian cosas distintas.
   */
  it('destaca la variante que queda con el margen bajo', async () => {
    await montar()

    expect(screen.getByText('Revisa los costos antes de confirmar')).toBeInTheDocument()
    expect(screen.getByText('margen bajo')).toBeInTheDocument()
  })

  it('avisa cuando el costo queda por encima del precio de venta', async () => {
    await montar({
      previa: previaDePrueba({
        lineas: [{
          varianteId: 1000, cantidad: 1, stockActual: 0, stockResultante: 1,
          costoUnitario: 40000, costoPromedioActual: 0, costoPromedioResultante: 40000,
          precioVenta: 32000, margenPorcentaje: -25,
          costoSuperaPrecio: true, margenBajo: true,
        }],
      }),
    })

    expect(screen.getByText('sobre el precio')).toBeInTheDocument()
  })

  /** No se confirma sin responder como se pago: es la pregunta que evita el descuadre. */
  it('no deja confirmar hasta que se dice cómo se pagó', async () => {
    const usuario = userEvent.setup()
    await montar()

    expect(screen.getByRole('button', { name: 'Primero indica cómo se pagó' })).toBeDisabled()

    await usuario.click(screen.getByLabelText('Transferencia o consignación'))

    expect(screen.getByRole('button', { name: 'Confirmar la recepción' })).toBeEnabled()
  })

  /**
   * NINGUN NUMERO DE LA PREVIA VUELVE AL SERVIDOR.
   *
   * Entre que se abrio esta pantalla y se apreto el boton, alguien pudo haber
   * vendido: la previa quedo vieja y el ledger tiene razon. Si la confirmacion
   * mandara los costos calculados aqui, el servidor escribiria numeros de un
   * inventario que ya no existe — y no se notaria, porque cuadrarian entre si.
   */
  it('al confirmar manda solo el id, ningún número de la previa', async () => {
    const usuario = userEvent.setup()
    let peticionDeRecepcion = null

    const espia = await montar({
      alRecibir: (ruta, opciones) => {
        peticionDeRecepcion = opciones
        return { cuerpo: { ...COMPRA, estado: 'RECIBIDA', total: 60000 } }
      },
    })

    await usuario.click(screen.getByLabelText('Transferencia o consignación'))
    await usuario.click(screen.getByRole('button', { name: 'Confirmar la recepción' }))

    await screen.findByText('La mercancía entró al inventario')

    expect(peticionDeRecepcion.method).toBe('POST')
    expect(peticionDeRecepcion.body).toBeUndefined()

    const rutas = espia.mock.calls.map(([ruta]) => ruta)
    expect(rutas).toContain('/api/v1/compras/901/recepcion')
    // Y no se registro ningun gasto: no se pago en efectivo.
    expect(rutas).not.toContain('/api/v1/caja/movimientos')
  })

  /**
   * Compras no escribe en caja. La pantalla hace dos llamadas independientes, y el
   * gasto pasa por el mismo endpoint que ya usa la caja — sin un camino nuevo desde
   * compras.
   */
  it('si se pagó en efectivo y hay caja abierta, registra el gasto aparte', async () => {
    const usuario = userEvent.setup()
    let gasto = null

    await montar({
      sesionDeCaja: { id: 7, estado: 'ABIERTA' },
      alRegistrarGasto: (ruta, opciones) => {
        gasto = JSON.parse(opciones.body)
        return { estado: 201, cuerpo: { id: 1 } }
      },
    })

    await usuario.click(screen.getByLabelText('Efectivo de la caja'))
    await usuario.click(screen.getByRole('button', { name: 'Confirmar la recepción' }))

    await screen.findByText('El gasto quedó registrado en la caja')

    expect(gasto.tipo).toBe('GASTO')
    // El monto es el que devolvio la recepcion, no el de la previa.
    expect(gasto.monto).toBe(60000)
    expect(gasto.concepto).toContain('C-000901')
  })

  /** Sin caja abierta no se inventa una sesión: se dice que queda pendiente. */
  it('sin caja abierta avisa que el gasto se registra al abrirla', async () => {
    const usuario = userEvent.setup()
    const espia = await montar()

    await usuario.click(screen.getByLabelText('Efectivo de la caja'))
    expect(await screen.findByText(/No hay una caja abierta/)).toBeInTheDocument()

    await usuario.click(screen.getByRole('button', { name: 'Confirmar la recepción' }))
    await screen.findByText('Falta registrar el gasto en la caja')

    expect(espia.mock.calls.map(([ruta]) => ruta)).not.toContain('/api/v1/caja/movimientos')
  })

  /**
   * El estado que no se puede callar: la mercancia entro y el gasto no quedo. Si la
   * pantalla mostrara un error a secas, quien lo vea va a creer que no se recibio
   * nada y lo va a intentar otra vez.
   */
  it('si la recepción sale bien y el gasto falla, lo dice sin confundirlos', async () => {
    const usuario = userEvent.setup()

    await montar({
      sesionDeCaja: { id: 7, estado: 'ABIERTA' },
      alRegistrarGasto: { estado: 500, cuerpo: { codigo: 'ERROR_INTERNO', error: 'Falló.' } },
    })

    await usuario.click(screen.getByLabelText('Efectivo de la caja'))
    await usuario.click(screen.getByRole('button', { name: 'Confirmar la recepción' }))

    expect(await screen.findByText('La mercancía entró al inventario')).toBeInTheDocument()
    expect(screen.getByText('La mercancía entró, pero el gasto no se registró')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar el gasto' })).toBeInTheDocument()
  })
})

describe('Anular una compra', () => {
  beforeEach(() => {
    contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
  })

  function PantallaAnular() {
    return (
      <AnularCompra
        compra={COMPRA}
        catalogo={useCatalogo()}
        alCerrar={() => {}}
        alHecho={async () => {}}
      />
    )
  }

  /**
   * Anular puede dejar el stock negativo, y no se bloquea: si la compra nunca llego,
   * el negativo es verdad y dice que se vendio de mas. Pero hay que verlo ANTES de
   * confirmar y con la variante nombrada — despues, es un numero raro en el catalogo
   * que nadie sabe de donde salio.
   */
  it('avisa qué variantes quedan en negativo, nombrándolas', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/compras/901/previa-anulacion': {
        cuerpo: {
          compraId: 901,
          consecutivo: 'C-000901',
          total: 60000,
          hayStockNegativo: true,
          lineas: [
            { varianteId: 1000, cantidadQueSeDevuelve: 10, stockActual: 7, stockResultante: -3, quedaNegativo: true },
            { varianteId: 1001, cantidadQueSeDevuelve: 1, stockActual: 4, stockResultante: 3, quedaNegativo: false },
          ],
        },
      },
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    }))

    render(<PantallaAnular />)

    expect(await screen.findByText('Estas variantes quedan con stock negativo')).toBeInTheDocument()

    // Nombrada, no "variante 1000".
    const aviso = screen.getByText('Estas variantes quedan con stock negativo').closest('.aviso')
    expect(within(aviso).getByText(/Rojo carmín/)).toBeInTheDocument()
    expect(within(aviso).getByText(/7 → -3/)).toBeInTheDocument()
    // La que no queda negativa no se nombra: seria ruido.
    expect(within(aviso).queryByText(/Nude/)).not.toBeInTheDocument()
  })

  it('exige motivo para anular', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/compras/901/previa-anulacion': {
        cuerpo: { compraId: 901, consecutivo: 'C-000901', total: 0, hayStockNegativo: false, lineas: [] },
      },
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
    }))

    const usuario = userEvent.setup()
    render(<PantallaAnular />)

    const boton = await screen.findByRole('button', { name: 'Anular y devolver el stock' })
    expect(boton).toBeDisabled()

    await usuario.type(screen.getByLabelText('¿Por qué se anula?'), 'Mercancía equivocada')
    expect(boton).toBeEnabled()
  })
})
