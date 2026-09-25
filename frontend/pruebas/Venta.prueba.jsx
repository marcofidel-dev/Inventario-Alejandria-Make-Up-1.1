import { render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  catalogoDePrueba, fetchFalso, PERMISOS_EMPLEADA, sesionAbierta, sesionDe, variante,
  ventaDePrueba,
} from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Venta, sugerenciasDeEfectivo } = await import('../src/pantallas/Venta.jsx')
const { useCatalogo } = await import('../src/catalogo/useCatalogo.js')

/**
 * El catalogo del mostrador. La cuarta variante es la que importa: TIENE historial
 * —se recibio una compra y despues se anulo— y NO tiene costo. Es el unico caso donde
 * `conHistorial` no alcanza, asi que sin ella la prueba del rechazo no demostraria
 * nada: el buscador ni siquiera la ofreceria.
 */
const CATALOGO = catalogoDePrueba({
  variantes: [
    variante({ id: 1000, productoId: 100, tono: 'Rojo carmín', precioVenta: 32000, stock: 7 }),
    variante({ id: 1001, productoId: 100, tono: 'Nude', precioVenta: 25000, stock: 4 }),
    variante({ id: 1002, productoId: 101, tono: null, tamano: '9 ml', precioVenta: 45000, stock: 2 }),
    variante({ id: 1003, productoId: 100, tono: 'Vino tinto', precioVenta: 32000, stock: 3,
      conHistorial: true, sinCosto: true }),
  ],
})

/** La pantalla con su catalogo real, filtrado en memoria como en la tienda. */
function PantallaDeVenta({ alIrA }) {
  return <Venta catalogo={useCatalogo()} alIrA={alIrA} />
}

/**
 * Monta el mostrador. `alCobrar` recibe el cuerpo ya parseado y devuelve la respuesta,
 * para que cada prueba decida que contesta el servidor en cada intento.
 */
async function montar({ sesion = sesionAbierta(), alCobrar, alIrA, alAbrirRecibo } = {}) {
  const enviados = []

  const espia = fetchFalso({
    // Antes que '/api/v1/ventas': fetchFalso resuelve por prefijo y se queda con la
    // primera que encaja.
    '/api/v1/ventas/500/recibo/apertura': alAbrirRecibo ?? { estado: 204 },

    '/api/v1/caja/sesiones/actual': () => (sesion
      ? { cuerpo: sesion }
      : { estado: 404, cuerpo: { codigo: 'NO_ENCONTRADO', error: 'No hay ninguna sesión.' } }),

    '/api/v1/catalogo': { cuerpo: CATALOGO },

    '/api/v1/ventas': (ruta, opciones) => {
      const cuerpo = JSON.parse(opciones.body)
      enviados.push(cuerpo)
      return alCobrar
        ? alCobrar(cuerpo, enviados.length)
        : { estado: 201, cuerpo: ventaDePrueba({ uuid: cuerpo.uuid }) }
    },
  })

  vi.stubGlobal('fetch', espia)
  render(<PantallaDeVenta alIrA={alIrA} />)
  await screen.findByRole('heading', { name: 'Vender' })
  return { espia, enviados }
}

/** Busca un producto y lo elige con el teclado, como en el mostrador. */
async function elegir(usuario, texto) {
  const buscador = screen.getByRole('combobox', { name: 'Buscar producto' })
  await usuario.clear(buscador)
  await usuario.type(buscador, texto)
  await usuario.keyboard('{Enter}')
}

/** Cobra en efectivo con el primer botón de sugerencia. */
async function cobrarEnEfectivo(usuario) {
  await usuario.click(screen.getByRole('button', { name: 'Cobrar' }))
}

const filasDelCarrito = () => {
  const tabla = screen.queryByRole('table')
  return tabla ? within(tabla).queryAllByRole('row').slice(1) : []
}

beforeEach(() => {
  contexto.sesion = sesionDe('EMPLEADA', PERMISOS_EMPLEADA)
})

describe('fallar temprano, no en la caja', () => {
  /**
   * Sin caja abierta no se arma el carrito. Dejar meter productos y rechazar al cobrar
   * seria pedirle a alguien un trabajo que se va a perder entero en el ultimo paso, con
   * la clienta ya esperando el total.
   */
  it('sin caja abierta no deja armar el carrito y lleva a caja', async () => {
    const usuario = userEvent.setup()
    const alIrA = vi.fn()
    await montar({ sesion: null, alIrA })

    expect(screen.queryByRole('combobox', { name: 'Buscar producto' })).not.toBeInTheDocument()
    expect(screen.getByText(/Todavía no se ha abierto la caja/)).toBeInTheDocument()

    await usuario.click(screen.getByRole('button', { name: 'Ir a abrir la caja' }))
    expect(alIrA).toHaveBeenCalledWith({ seccion: 'caja', pestana: null })
  })

  /** La caja de ayer sin cerrar es el mismo bloqueo, y dice de qué día es. */
  it('con la caja de un día anterior tampoco deja vender', async () => {
    await montar({
      sesion: sesionAbierta({ esDeUnDiaAnterior: true, fechaApertura: '2026-08-14T08:12:00' }),
    })

    expect(screen.queryByRole('combobox', { name: 'Buscar producto' })).not.toBeInTheDocument()
    expect(screen.getByText(/Quedó abierta la caja del 2026-08-14/)).toBeInTheDocument()
  })

  /**
   * LA VARIANTE SIN COSTO SE RECHAZA AL AGREGAR, no al cobrar. La bandera viene en el
   * catalogo, asi que la linea ni siquiera entra y no hace falta preguntarle al
   * servidor. Si esto se dejara para el cobro, el 409 llegaria con el carrito armado.
   */
  it('una variante sin costo no entra al carrito', async () => {
    const usuario = userEvent.setup()
    const { espia } = await montar()

    await elegir(usuario, 'vino')

    expect(await screen.findByText(/no tiene costo registrado/)).toBeInTheDocument()
    expect(screen.getByText(/recibir la compra pendiente/)).toBeInTheDocument()
    expect(filasDelCarrito()).toHaveLength(0)
    expect(espia.mock.calls.filter(([ruta]) => ruta === '/api/v1/ventas')).toHaveLength(0)

    // Y la de al lado, que sí tiene costo, entra sin problema.
    await elegir(usuario, 'carmín')
    expect(filasDelCarrito()).toHaveLength(1)
  })
})

describe('el uuid', () => {
  /**
   * EL BUG QUE DEJARIA VENTAS SIN REGISTRAR COBRANDO IGUAL. Vender dos veces seguidas
   * el mismo producto a dos clientas distintas es rutina en el mostrador. Si el uuid se
   * reutilizara, el servidor devolveria la venta anterior, el segundo cobro se tragaria
   * en silencio y la tienda entregaria dos productos habiendo cobrado uno. El descuadre
   * aparece de noche, cuadrando la caja, sin forma de saber de donde salio.
   */
  it('dos ventas seguidas del mismo producto crean dos ventas distintas', async () => {
    const usuario = userEvent.setup()
    const { enviados } = await montar()

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)
    await screen.findByText(/V-000123 cobrada/)

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    await waitFor(() => expect(enviados).toHaveLength(2))
    expect(enviados[0].uuid).not.toBe(enviados[1].uuid)
  })

  /**
   * EL UUID SOBREVIVE AL FALLO, y es el unico caso donde la idempotencia sirve de algo.
   *
   * Si la peticion se cae por red o timeout, la venta puede haber quedado escrita
   * entera del otro lado —la respuesta se perdio despues del commit— y la pantalla no
   * tiene forma de distinguirlo de un fallo donde no paso nada. El reintento tiene que
   * llevar el MISMO uuid: asi el servidor devuelve la que ya existe en vez de cobrar de
   * nuevo, descontar el inventario de nuevo y meter la plata en el cajon de nuevo.
   */
  it('tras un fallo de red el reintento manda el mismo uuid', async () => {
    const usuario = userEvent.setup()
    const { enviados } = await montar({
      // El primer intento se cae sin respuesta, como un cable suelto.
      alCobrar: (cuerpo, numeroDeIntento) => {
        if (numeroDeIntento === 1) throw new TypeError('Failed to fetch')
        return { estado: 200, cuerpo: ventaDePrueba({ uuid: cuerpo.uuid }) }
      },
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    // El carrito se queda: reintentar es pulsar una vez, no rearmar la venta.
    expect(filasDelCarrito()).toHaveLength(1)

    await cobrarEnEfectivo(usuario)
    await screen.findByText(/V-000123 cobrada/)

    expect(enviados).toHaveLength(2)
    expect(enviados[1].uuid).toBe(enviados[0].uuid)
  })

  /**
   * Y el 200 de esa recuperacion NO alarma: es exactamente lo que se buscaba. El aviso
   * queda para el 200 que llega sin fallo previo, que si es un uuid reutilizado.
   */
  it('el 200 que recupera un cobro fallido no se anuncia como problema', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo, numeroDeIntento) => {
        if (numeroDeIntento === 1) throw new TypeError('Failed to fetch')
        return { estado: 200, cuerpo: ventaDePrueba({ uuid: cuerpo.uuid }) }
      },
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)
    await screen.findByText('No hay conexión con el servidor')
    await cobrarEnEfectivo(usuario)
    await screen.findByText(/V-000123 cobrada/)

    expect(screen.queryByText(/Esta venta ya estaba registrada/)).not.toBeInTheDocument()
  })

  /** En cambio un 200 sin fallo previo sí se avisa: ahí un cobro se está tragando. */
  it('un 200 sin fallo previo avisa que la venta ya estaba registrada', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo) => ({ estado: 200, cuerpo: ventaDePrueba({ uuid: cuerpo.uuid }) }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    expect(await screen.findByText(/Esta venta ya estaba registrada/)).toBeInTheDocument()
  })
})

describe('cobrar', () => {
  /**
   * EL CAMBIO QUE SE GUARDA ES EL DEL SERVIDOR. Si difiere del que mostro la pantalla,
   * manda el del servidor y se dice: los dos salen de la misma resta, asi que no
   * coincidir significa que el total cobrado no era el que se estaba viendo. Taparlo
   * dejaria a alguien devolviendo mal la plata sin enterarse nunca.
   */
  it('el cambio que queda en pantalla es el del servidor', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo) => ({
        estado: 201,
        // La pantalla venia mostrando 18.000 (50.000 - 32.000). El servidor dice otra.
        cuerpo: ventaDePrueba({ uuid: cuerpo.uuid, cambio: 17500 }),
      }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^50.000$/ }))
    expect(screen.getByText('18.000')).toBeInTheDocument()

    await cobrarEnEfectivo(usuario)

    const comprobante = await screen.findByText(/V-000123 cobrada/)
    expect(comprobante.parentElement.textContent).toContain('17.500')
    expect(await screen.findByText(/no coincide con el que mostró la pantalla/))
      .toBeInTheDocument()
  })

  /** Un doble clic no cobra dos veces: el botón se bloquea mientras hay envío. */
  it('el cobro se envía una sola vez aunque se pulse dos veces seguidas', async () => {
    const usuario = userEvent.setup()
    let resolver
    const { enviados } = await montar({
      alCobrar: (cuerpo) => new Promise((cumplir) => {
        resolver = () => cumplir({ estado: 201, cuerpo: ventaDePrueba({ uuid: cuerpo.uuid }) })
      }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))

    const boton = screen.getByRole('button', { name: 'Cobrar' })
    await usuario.click(boton)
    await usuario.click(screen.getByRole('button', { name: 'Cobrando…' }))

    expect(enviados).toHaveLength(1)
    resolver()
    await screen.findByText(/V-000123 cobrada/)
  })

  /**
   * Despues de cobrar la pantalla queda lista para la clienta siguiente. SIN MODAL: un
   * modal que exige un clic para continuar son cientos de clics al dia.
   */
  it('después de cobrar la pantalla se limpia sola y no pide ningún clic', async () => {
    const usuario = userEvent.setup()
    await montar()

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)
    await screen.findByText(/V-000123 cobrada/)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(filasDelCarrito()).toHaveLength(0)
    expect(screen.getByRole('combobox', { name: 'Buscar producto' })).toBeInTheDocument()
  })

  /**
   * El aviso de stock negativo no se le pinta a la clienta como si la venta hubiera
   * fallado: la venta salio bien. Va en alerta y sin role="alert", detras del exito.
   */
  it('avisa del stock negativo sin presentarlo como un error', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo) => ({
        estado: 201,
        cuerpo: ventaDePrueba({
          uuid: cuerpo.uuid,
          variantesEnNegativo: [
            { varianteId: 1000, descripcion: 'Loréal Labial mate Rojo carmín', stock: -2 },
          ],
        }),
      }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    const aviso = await screen.findByText(/Quedó stock en negativo/)
    expect(screen.getByText(/V-000123 cobrada/)).toBeInTheDocument()
    expect(aviso.closest('.aviso')).toHaveClass('aviso--alerta')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('todo con teclado', () => {
  /**
   * Buscar, elegir, cantidad y siguiente producto sin tocar el raton. El foco vuelve
   * SOLO al buscador tras cada linea, porque el gesto que se repite doscientas veces al
   * dia es buscar el producto siguiente.
   */
  it('el foco vuelve al buscador después de teclear la cantidad', async () => {
    const usuario = userEvent.setup()
    await montar()

    await elegir(usuario, 'carmín')

    const cantidad = screen.getByLabelText(/^Cantidad de/)
    await waitFor(() => expect(cantidad).toHaveFocus())

    await usuario.clear(cantidad)
    await usuario.type(cantidad, '3{Enter}')

    await waitFor(() => expect(
      screen.getByRole('combobox', { name: 'Buscar producto' }),
    ).toHaveFocus())
    expect(within(filasDelCarrito()[0]).getByText('96.000')).toBeInTheDocument()
  })

  /** Elegir dos veces el mismo producto suma, no duplica la línea. */
  it('elegir dos veces el mismo producto suma uno', async () => {
    const usuario = userEvent.setup()
    await montar()

    await elegir(usuario, 'carmín')
    await elegir(usuario, 'carmín')

    expect(filasDelCarrito()).toHaveLength(1)
    expect(screen.getByLabelText(/^Cantidad de/)).toHaveValue('2')
  })

  /** Enter en el buscador vacío pasa al cobro, con el carrito ya armado. */
  it('Enter en el buscador vacío lleva al método de pago', async () => {
    const usuario = userEvent.setup()
    await montar()

    await elegir(usuario, 'carmín')
    // Se espera a que el encadenado del foco termine antes de volver al buscador: si
    // no, el frame que enfoca la cantidad llega despues y se lleva el Enter.
    await waitFor(() => expect(screen.getByLabelText(/^Cantidad de/)).toHaveFocus())

    const buscador = screen.getByRole('combobox', { name: 'Buscar producto' })
    buscador.focus()
    await usuario.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByRole('radio', { name: 'Efectivo' })).toHaveFocus())
  })
})

describe('con cuánto paga', () => {
  /**
   * Los botones son los billetes con los que de verdad se paga, calculados sobre el
   * total. Una lista fija de denominaciones obligaria a teclear el monto justo en la
   * mitad de las ventas.
   */
  it('ofrece el exacto, el siguiente mil y los redondeos de arriba', () => {
    expect(sugerenciasDeEfectivo(102300)).toEqual([102300, 103000, 105000, 110000])
    // Con un total ya redondo no se repite el mismo botón cuatro veces.
    expect(sugerenciasDeEfectivo(100000)).toEqual([100000])
    expect(sugerenciasDeEfectivo(0)).toEqual([])
  })

  /** Nunca se ofrece menos que el total: eso no sería pagar. */
  it('ninguna sugerencia es menor que el total', () => {
    for (const total of [1, 999, 1000, 33350, 102300, 1250000]) {
      expect(sugerenciasDeEfectivo(total).every((monto) => monto >= total)).toBe(true)
    }
  })
})

describe('el stock de la búsqueda', () => {
  /**
   * DESPUES DE CINCUENTA VENTAS LOS NUMEROS EN PANTALLA SERIAN FALSOS. El catalogo se
   * carga una vez —eso es lo que hace instantanea la busqueda— asi que el stock se queda
   * en el de la manana salvo que alguien lo baje. La respuesta del cobro ya dice que
   * salio: se descuenta de ahi, sin una sola llamada de mas.
   */
  it('el cobro descuenta del catálogo en memoria, sin volver a pedirlo', async () => {
    const espia = fetchFalso({ '/api/v1/catalogo': { cuerpo: CATALOGO } })
    vi.stubGlobal('fetch', espia)

    const { result } = renderHook(() => useCatalogo())
    await waitFor(() => expect(result.current.filas.length).toBeGreaterThan(0))

    const antes = result.current.filas.find((f) => f.id === 1000).stock
    const llamadas = espia.mock.calls.length

    result.current.descontarStock([
      { varianteId: 1000, cantidad: 3 },
      { varianteId: 1001, cantidad: 1 },
    ])

    await waitFor(() => expect(
      result.current.filas.find((f) => f.id === 1000).stock,
    ).toBe(antes - 3))
    expect(result.current.filas.find((f) => f.id === 1001).stock).toBe(3)
    expect(result.current.filas.find((f) => f.id === 1002).stock).toBe(2)
    expect(espia.mock.calls).toHaveLength(llamadas)
  })
})

describe('el recibo, con la clienta enfrente', () => {
  /**
   * EL MOMENTO EN QUE SE NECESITA ES ESTE. Buscar la venta en el listado del dia para
   * sacar el papel que se acaba de pedir es un rodeo con alguien esperando en el
   * mostrador, asi que el boton va en la franja del cobro.
   *
   * NO ES UN MODAL Y NO INTERRUMPE: la franja ya esta ahi y la pantalla ya quedo lista
   * para la clienta siguiente. Quien no quiere el recibo no tiene que cerrar nada.
   */
  it('tras cobrar ofrece ver el recibo, y abrirlo es un POST al backend', async () => {
    const usuario = userEvent.setup()
    const abrirVentana = vi.fn()
    vi.stubGlobal('open', abrirVentana)

    const { espia } = await montar({
      alCobrar: (cuerpo) => ({
        estado: 201,
        cuerpo: ventaDePrueba({ uuid: cuerpo.uuid, rutaRecibo: 'recibos/2026/08/V-000123.pdf' }),
      }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    await usuario.click(await screen.findByRole('button', { name: 'Ver recibo' }))

    const llamadas = espia.mock.calls.map(([ruta, o]) => `${o?.method} ${ruta}`)
    expect(llamadas).toContain('POST /api/v1/ventas/500/recibo/apertura')
    // El visor es el del sistema: si esto se convierte en un enlace o un window.open,
    // en modo app se abre una ventana de navegador suelta encima del mostrador.
    expect(abrirVentana).not.toHaveBeenCalled()
  })

  /**
   * Si el PDF no se genero —el generador atrapa su fallo y deja la venta valida— no
   * hay nada que ver. Ofrecerlo para que despues conteste que no existe es peor que no
   * ofrecerlo, justo en el momento de menos paciencia.
   */
  it('sin recibo generado no ofrece verlo', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo) => ({
        estado: 201,
        cuerpo: ventaDePrueba({ uuid: cuerpo.uuid, rutaRecibo: null }),
      }),
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)

    await screen.findByText(/cobrada/)
    expect(screen.queryByRole('button', { name: 'Ver recibo' })).not.toBeInTheDocument()
  })

  /** Un recibo que no abre no vuelve dudosa la venta: la plata ya entro. */
  it('si el recibo no abre, la venta sigue anunciada como cobrada', async () => {
    const usuario = userEvent.setup()
    await montar({
      alCobrar: (cuerpo) => ({
        estado: 201,
        cuerpo: ventaDePrueba({ uuid: cuerpo.uuid, rutaRecibo: 'recibos/2026/08/V-000123.pdf' }),
      }),
      alAbrirRecibo: {
        estado: 409,
        cuerpo: { codigo: 'SIN_VISOR', error: 'Este equipo no tiene con qué abrir el PDF.' },
      },
    })

    await elegir(usuario, 'carmín')
    await usuario.click(screen.getByRole('button', { name: /^32.000$/ }))
    await cobrarEnEfectivo(usuario)
    await usuario.click(await screen.findByRole('button', { name: 'Ver recibo' }))

    const aviso = await screen.findByText(/no tiene con qué abrir el PDF/)
    expect(aviso.closest('.aviso')).toHaveClass('aviso--alerta')
    expect(screen.getByText(/cobrada/)).toBeInTheDocument()
  })
})
