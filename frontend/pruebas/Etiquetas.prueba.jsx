import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  catalogoDePrueba, comprasDePrueba, fetchFalso, movimientoDePrueba, PERMISOS_DUENA,
  proveedoresDePrueba, resumenDeVenta, sesionAbierta, sesionDe,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Armazon } = await import('../src/pantallas/Armazon.jsx')
const { Caja } = await import('../src/pantallas/Caja.jsx')
const { Compras } = await import('../src/pantallas/Compras.jsx')
const { Ventas } = await import('../src/pantallas/Ventas.jsx')
const { etiqueta, VALORES_CON_ETIQUETA } = await import('../src/etiquetas.js')
const { useCatalogo } = await import('../src/catalogo/useCatalogo.js')
const { useCompras } = await import('../src/compras/useCompras.js')

/**
 * UN IDENTIFICADOR DE CODIGO EN PANTALLA ES UN BUG.
 *
 * `DUENA` se llama asi porque es un identificador de Java y el valor de un CHECK en
 * SQLite; cambiarlo exigiria una migracion y no la vale. Lo que si tiene que cambiar
 * es lo que se pinta, y esa distincion no se nota mirando el codigo: `{usuario.rol}`
 * se lee perfectamente razonable hasta que alguien ve "DUENA" en el encabezado.
 *
 * La guarda recorre TODO lo renderizado buscando la firma inconfundible de un
 * identificador —mayusculas sostenidas con guion bajo— en vez de comprobar valor por
 * valor: asi cubre tambien los enums que todavia no existen. `etiqueta()` humaniza lo
 * que no tiene traduccion explicita, asi que lo que esta prueba caza de verdad es el
 * sitio que se olvido de llamarla.
 */
const IDENTIFICADOR = /\b[A-ZÁÉÍÓÚÑ]{2,}_[A-ZÁÉÍÓÚÑ_]{2,}\b/

/** Todo el texto de la pantalla, incluido el de los campos controlados por React. */
function textoRenderizado() {
  const campos = [...document.querySelectorAll('input, textarea')]
    .map((campo) => campo.value)
    .join(' ')
  return `${document.body.textContent} ${campos}`
}

function exigirSinIdentificadores() {
  const encontrado = IDENTIFICADOR.exec(textoRenderizado())
  expect(encontrado?.[0], 'hay un identificador de código en pantalla').toBeUndefined()
}

beforeEach(() => {
  contexto.sesion = sesionDe('DUENA', PERMISOS_DUENA)
})

describe('ningún identificador de código llega a la pantalla', () => {
  it('el encabezado dice el rol con palabras, no DUENA', async () => {
    render(
      <Armazon vista={{ seccion: 'inventario', pestana: 'productos' }} alCambiarVista={() => {}}>
        <p>contenido</p>
      </Armazon>,
    )

    expect(screen.getByText('Dueña')).toBeInTheDocument()
    expect(screen.queryByText('DUENA')).not.toBeInTheDocument()
    exigirSinIdentificadores()
  })

  /**
   * VENTA_EFECTIVO es el caso que de verdad importa: es el unico tipo de movimiento
   * que se genera solo —lo escribe el cobro, no una persona— y por eso es el que
   * nadie mira hasta que aparece en la pantalla de caja con su guion bajo.
   */
  it('los movimientos de caja no muestran VENTA_EFECTIVO', async () => {
    const sesion = sesionAbierta()
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/caja/sesiones/actual': { cuerpo: sesion },
      [`/api/v1/caja/sesiones/${sesion.id}/movimientos`]: {
        cuerpo: [
          movimientoDePrueba({ id: 1, tipo: 'VENTA_EFECTIVO', concepto: 'Venta V-000123' }),
          movimientoDePrueba({ id: 2, tipo: 'RETIRO', concepto: 'Consignación' }),
        ],
      },
      '/api/v1/caja/sesiones': { cuerpo: [sesion] },
    }))

    render(<Caja />)
    await screen.findByText('Venta en efectivo')
    exigirSinIdentificadores()
  })

  it('el listado de compras dice el estado con palabras', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/catalogo': { cuerpo: catalogoDePrueba() },
      '/api/v1/proveedores': { cuerpo: proveedoresDePrueba() },
      '/api/v1/compras': { cuerpo: comprasDePrueba() },
    }))

    function PantallaCompras() {
      return <Compras catalogo={useCatalogo()} compras={useCompras()} />
    }
    render(<PantallaCompras />)
    await screen.findByRole('heading', { name: 'Compras' })
    await waitFor(() => expect(screen.getAllByText('Borrador').length).toBeGreaterThan(0))
    exigirSinIdentificadores()
  })

  it('el listado de ventas dice el método de pago con palabras', async () => {
    vi.stubGlobal('fetch', fetchFalso({
      '/api/v1/ventas': {
        cuerpo: [
          resumenDeVenta({ id: 1, metodoPago: 'DAVIPLATA' }),
          resumenDeVenta({ id: 2, consecutivo: 'V-000124', metodoPago: 'TRANSFERENCIA' }),
        ],
      },
    }))

    render(<Ventas />)
    await screen.findByText('Daviplata')
    exigirSinIdentificadores()
  })
})

describe('etiqueta()', () => {
  /**
   * La lista del documento de mejoras, literal. No es redundante con la de arriba: la
   * guarda de pantalla solo ve lo que alguna prueba renderiza, y esto fija la
   * traduccion exacta —con tilde y con eñe— de los valores que se sabe que aparecen.
   */
  it('traduce los enums que llegan a la interfaz', () => {
    expect(etiqueta('DUENA')).toBe('Dueña')
    expect(etiqueta('EMPLEADA')).toBe('Empleada')
    expect(etiqueta('VENTA_EFECTIVO')).toBe('Venta en efectivo')
    expect(etiqueta('CARGA_INICIAL')).toBe('Carga inicial')
    expect(etiqueta('BORRADOR')).toBe('Borrador')
    expect(etiqueta('RECIBIDA')).toBe('Recibida')
    expect(etiqueta('DESCARTADA')).toBe('Descartada')
    expect(etiqueta('ANULADA')).toBe('Anulada')
    expect(etiqueta('COMPLETADA')).toBe('Completada')
    expect(etiqueta('DAVIPLATA')).toBe('Daviplata')
    expect(etiqueta('TRANSFERENCIA')).toBe('Transferencia')
  })

  /** Ninguna etiqueta puede tener la forma de lo que viene a reemplazar. */
  it('ninguna etiqueta conserva el guion bajo', () => {
    for (const valor of VALORES_CON_ETIQUETA) {
      expect(etiqueta(valor)).not.toMatch(IDENTIFICADOR)
    }
  })

  /**
   * La red de seguridad: un enum nuevo que nadie tradujo se ve mal escrito, no como
   * un identificador. Es a proposito que no devuelva el valor crudo — el dia que se
   * agregue un tipo de movimiento, lo peor que puede pasar es una tilde que falta.
   */
  it('un valor sin traducir se humaniza en vez de salir crudo', () => {
    expect(etiqueta('ALGO_NUEVO_SIN_TRADUCIR')).toBe('Algo nuevo sin traducir')
    expect(etiqueta(null)).toBe('')
  })
})
