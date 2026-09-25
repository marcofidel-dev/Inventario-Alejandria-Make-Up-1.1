import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  fetchFalso, panelDePrueba, panelSinVentas, resumenDeMetricas, sinRotacionDePrueba,
  vencimientoDePrueba,
} from './ayudas.jsx'

const { Metricas, SinRotacion } = await import('../src/pantallas/Metricas.jsx')

/** Sabado 15 de agosto de 2026. Solo se congela la fecha: los temporizadores siguen reales. */
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 7, 15, 12, 0, 0))
})

async function montar({ panel = panelDePrueba(), vencimientos = [], respuestaPanel } = {}) {
  const espia = fetchFalso({
    '/api/v1/metricas/panel': respuestaPanel ?? { cuerpo: panel },
    '/api/v1/metricas/vencimientos': { cuerpo: vencimientos },
  })
  vi.stubGlobal('fetch', espia)
  render(<Metricas />)
  return espia
}

const rutas = (espia) => espia.mock.calls.map(([ruta]) => ruta)
const delPanel = (espia) => rutas(espia).filter((ruta) => ruta.includes('/metricas/panel'))
const tarjeta = (titulo) =>
  screen.getByText(titulo, { selector: '.tarjeta__titulo' }).closest('.tarjeta')
const esperarPanel = () => screen.findByText('Ingreso', { selector: '.tarjeta__titulo' })

describe('la llamada', () => {
  /**
   * UNA SOLA. Con pool de una conexion el backend serializa las consultas: nueve
   * peticiones sueltas se sentirian lentas y no habria forma de esconderlo. Los dos
   * detalles —vencimientos y sin rotacion— no se piden hasta que alguien los abre.
   */
  it('pide el panel completo en una llamada y nada más', async () => {
    const espia = await montar()
    await esperarPanel()

    expect(rutas(espia)).toEqual(['/api/v1/metricas/panel?periodo=DIA&fecha=2026-08-15'])
  })

  it('cambiar de periodo hace una sola llamada nueva, con ese periodo', async () => {
    const usuario = userEvent.setup()
    const espia = await montar()
    await esperarPanel()

    await usuario.click(screen.getByRole('tab', { name: 'Semana' }))
    await waitFor(() => expect(delPanel(espia)).toHaveLength(2))

    expect(delPanel(espia)[1]).toBe('/api/v1/metricas/panel?periodo=SEMANA&fecha=2026-08-15')
  })

  it('cambiar la fecha hace una sola llamada nueva, con esa fecha', async () => {
    const espia = await montar()
    await esperarPanel()

    fireEvent.change(screen.getByLabelText('Fecha'), { target: { value: '2026-07-01' } })
    await waitFor(() => expect(delPanel(espia)).toHaveLength(2))

    expect(delPanel(espia)[1]).toBe('/api/v1/metricas/panel?periodo=DIA&fecha=2026-07-01')
  })
})

describe('navegar hacia atrás y hacia adelante', () => {
  /**
   * Cada flecha salta un periodo del tipo elegido. El mes parte del dia 1: un 31 de
   * enero mas un mes seria 3 de marzo y febrero no se podria ver nunca.
   */
  it.each([
    ['DIA', 'Día', '2026-08-15', '2026-08-14'],
    ['SEMANA', 'Semana', '2026-08-15', '2026-08-03'],
    ['MES', 'Mes', '2026-08-15', '2026-07-01'],
    ['MES', 'Mes', '2026-03-31', '2026-02-01'],
  ])('atrás en %s (%s) desde %s va a %s', async (periodo, texto, desde, esperada) => {
    const usuario = userEvent.setup()
    const espia = await montar()
    await esperarPanel()

    fireEvent.change(screen.getByLabelText('Fecha'), { target: { value: desde } })
    await usuario.click(screen.getByRole('tab', { name: texto }))
    await usuario.click(screen.getByRole('button', { name: 'Periodo anterior' }))

    await waitFor(() => expect(delPanel(espia).at(-1))
      .toBe(`/api/v1/metricas/panel?periodo=${periodo}&fecha=${esperada}`))
  })

  it('no deja avanzar más allá del periodo que contiene hoy', async () => {
    const usuario = userEvent.setup()
    await montar()
    await esperarPanel()

    expect(screen.getByRole('button', { name: 'Periodo siguiente' })).toBeDisabled()

    // Una semana atrás sí puede avanzar, y el tope vuelve a aparecer al llegar a hoy.
    await usuario.click(screen.getByRole('tab', { name: 'Semana' }))
    expect(screen.getByRole('button', { name: 'Periodo siguiente' })).toBeDisabled()
    await usuario.click(screen.getByRole('button', { name: 'Periodo anterior' }))
    expect(screen.getByRole('button', { name: 'Periodo siguiente' })).toBeEnabled()
  })
})

describe('el resumen', () => {
  it('muestra cada cifra con su variación contra el periodo anterior', async () => {
    await montar()
    await esperarPanel()

    // Ingreso 600.000 contra 500.000: sube 20 %, y el numero anterior se ve al lado.
    const ingreso = within(tarjeta('Ingreso'))
    expect(ingreso.getByText('$ 600.000')).toBeInTheDocument()
    expect(ingreso.getByText('Sube 20 %')).toBeInTheDocument()
    expect(ingreso.getByText(/antes \$ 500\.000/)).toBeInTheDocument()

    expect(within(tarjeta('Ventas')).getByText('Sube 20 %')).toBeInTheDocument()
    expect(within(tarjeta('Unidades')).getByText('Sube 20 %')).toBeInTheDocument()
    // El margen porcentual se compara en puntos: 40 contra 40 es "sin cambio".
    expect(within(tarjeta('Margen %')).getByText('Sin cambio')).toBeInTheDocument()
  })

  it('una baja se dice como baja', async () => {
    await montar({ panel: panelDePrueba({
      actual: resumenDeMetricas({ ingreso: 400000, margen: 100000, margenPorcentaje: 25 }),
    }) })
    await esperarPanel()

    expect(within(tarjeta('Ingreso')).getByText('Baja 20 %')).toBeInTheDocument()
    expect(within(tarjeta('Margen %')).getByText('Baja 15 puntos')).toBeInTheDocument()
  })

  /**
   * NULL ES "SIN DATOS", NO CERO POR CIENTO. Sin ingreso no hay sobre que calcular el
   * margen: un "0 %" diria que se vendio sin ganar nada, y es otra cosa.
   */
  it('el margen porcentual nulo se muestra como "—" y "sin ventas", nunca como 0 %', async () => {
    await montar({ panel: panelSinVentas() })
    await esperarPanel()

    const margen = within(tarjeta('Margen %'))
    expect(margen.getByText('—')).toBeInTheDocument()
    expect(margen.getByText('sin ventas')).toBeInTheDocument()
    expect(margen.queryByText(/0 %/)).not.toBeInTheDocument()
  })

  /**
   * Comparar contra un periodo sin ventas no dice nada: sobre una base en cero todo
   * "sube" un infinito por ciento. El valor de hoy se muestra solo.
   */
  it('si el periodo anterior no tuvo ventas, muestra el valor solo, sin flechas ni porcentaje', async () => {
    await montar({ panel: panelDePrueba({
      anterior: { ventas: 0, unidades: 0, ingreso: 0, costo: 0, margen: 0, margenPorcentaje: null },
    }) })
    await esperarPanel()

    expect(within(tarjeta('Ingreso')).getByText('$ 600.000')).toBeInTheDocument()
    expect(screen.queryByText(/^Sube/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Baja/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Sin cambio/)).not.toBeInTheDocument()
    expect(screen.queryByText(/antes/)).not.toBeInTheDocument()
    // Y dice por que no compara, en vez de dejar la duda de si algo fallo.
    expect(screen.getByText(/periodo anterior no tuvo ventas/)).toBeInTheDocument()
  })

  it('un día sin ventas se ve completo, con ceros y sin ningún error', async () => {
    await montar({ panel: panelSinVentas() })
    await esperarPanel()

    expect(within(tarjeta('Ventas')).getByText('0')).toBeInTheDocument()
    expect(within(tarjeta('Ingreso')).getByText('$ 0')).toBeInTheDocument()
    // Todas las secciones siguen ahi: la pantalla no se reemplaza por "no hay datos".
    for (const titulo of ['Ventas por método de pago', 'Lo más vendido', 'Inventario', 'Vencimientos']) {
      expect(screen.getByRole('heading', { name: titulo })).toBeInTheDocument()
    }
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('el resto del panel', () => {
  it('lista los métodos de pago con su total y su cantidad', async () => {
    await montar()
    await esperarPanel()

    const seccion = within(screen.getByRole('heading', { name: 'Ventas por método de pago' })
      .closest('section'))
    expect(seccion.getByText('Efectivo')).toBeInTheDocument()
    expect(seccion.getByText('$ 400.000')).toBeInTheDocument()
    expect(seccion.getByText(/8 ventas/)).toBeInTheDocument()
    expect(seccion.getByText('Nequi')).toBeInTheDocument()
  })

  /**
   * DOS LISTAS, CADA UNA EN SU ORDEN. Lo que mas sale no siempre es lo que mas deja: si
   * la pantalla reordenara o mostrara una sola, esa diferencia —que es todo el punto—
   * desapareceria.
   */
  it('muestra dos rankings y respeta el orden de cada uno', async () => {
    await montar()
    await esperarPanel()

    const [porUnidades, porMargen] = screen.getAllByRole('table').slice(0, 2)
    const primeraFila = (tabla) => within(tabla).getAllByRole('row')[1].textContent

    expect(primeraFila(porUnidades)).toContain('Delineador')
    expect(primeraFila(porMargen)).toContain('Base líquida')
    expect(screen.getByRole('heading', { name: 'Por unidades' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Por margen aportado' })).toBeInTheDocument()
    expect(screen.getByText(/no siempre es lo que más deja/)).toBeInTheDocument()
  })

  it('el inventario muestra valor, unidades y las variantes bajo el mínimo', async () => {
    await montar()
    await esperarPanel()

    expect(within(tarjeta('Valor a costo')).getByText('$ 8.450.000')).toBeInTheDocument()
    expect(within(tarjeta('Unidades en stock')).getByText('640')).toBeInTheDocument()

    const filaBaja = screen.getByText('Maybelline|Máscara|Negra').closest('tr')
    expect(within(filaBaja).getByText('bajo')).toBeInTheDocument()
    // Negativo no es "bajo": uno dice "hay que reponer" y el otro "falta averiguar algo".
    const filaNegativa = screen.getByText('Loréal|Labial mate|Nude').closest('tr')
    expect(within(filaNegativa).getByText('negativo')).toBeInTheDocument()
    expect(within(filaNegativa).queryByText('bajo')).not.toBeInTheDocument()
  })

  it('sin variantes bajo el mínimo lo dice en positivo, no deja la sección en blanco', async () => {
    const panel = panelDePrueba()
    panel.inventario.variantesBajoMinimo = []
    await montar({ panel })
    await esperarPanel()

    expect(screen.getByText('Todo por encima del mínimo.')).toBeInTheDocument()
  })

  /**
   * La agrupacion viene en la respuesta y la pantalla la dice, pero discreta: no en el
   * cuerpo, sino disponible para quien pregunta por que un dia no cuadra con el cierre.
   */
  it('aclara que agrupa por fecha de venta y no por sesión de caja', async () => {
    await montar()
    await esperarPanel()

    const nota = screen.getByText('¿Por qué un número no cuadra con el cierre de caja?')
      .closest('details')
    expect(nota).not.toHaveAttribute('open')
    expect(within(nota).getByText(/no por sesión de caja/)).toBeInTheDocument()
    expect(within(nota).getByText(/de un día para otro/)).toBeInTheDocument()
  })
})

describe('vencimientos', () => {
  it('el panel trae los conteos y pinta de estado solo lo que tiene algo', async () => {
    await montar()
    await esperarPanel()

    expect(tarjeta('Vencidos')).toHaveClass('tarjeta--error')
    expect(within(tarjeta('Vencidos')).getByText('2')).toBeInTheDocument()
    expect(tarjeta('Vencen en 30 días o menos')).toHaveClass('tarjeta--alerta')
    expect(tarjeta('Entre 31 y 60 días')).not.toHaveClass('tarjeta--error', 'tarjeta--alerta')
    // Un cero no alarma: "0 vencidos" en rojo entrena a dejar de mirar el rojo.
    expect(tarjeta('Entre 61 y 90 días')).toHaveClass('tarjeta')
  })

  it('un cero en vencidos no se pinta de error', async () => {
    const panel = panelDePrueba({ vencimientos: { vencidos: 0, hasta30: 0, entre31y60: 0, entre61y90: 0 } })
    await montar({ panel })
    await esperarPanel()

    expect(tarjeta('Vencidos')).not.toHaveClass('tarjeta--error')
    expect(tarjeta('Vencen en 30 días o menos')).not.toHaveClass('tarjeta--alerta')
  })

  /** El detalle es una llamada aparte, y solo cuando alguien la pide. */
  it('el detalle se pide al pulsar "Ver detalle", no antes', async () => {
    const usuario = userEvent.setup()
    const espia = await montar({ vencimientos: [
      vencimientoDePrueba(),
      vencimientoDePrueba({ varianteId: 11, descripcion: 'Loréal|Sérum|30 ml',
        fechaVencimiento: '2026-09-20', diasParaVencer: 36, stock: 2, valorACosto: 90000 }),
    ] })
    await esperarPanel()
    expect(rutas(espia).some((ruta) => ruta.includes('vencimientos'))).toBe(false)

    await usuario.click(screen.getByRole('button', { name: 'Ver detalle' }))

    expect(await screen.findByText('Essence|Sombra|Bronce')).toBeInTheDocument()
    expect(rutas(espia).filter((ruta) => ruta.includes('vencimientos'))).toHaveLength(1)
    const vencida = screen.getByText('Essence|Sombra|Bronce').closest('tr')
    expect(within(vencida).getByText('hace 14 días')).toBeInTheDocument()
    expect(within(screen.getByText('Loréal|Sérum|30 ml').closest('tr')).getByText('en 36 días'))
      .toBeInTheDocument()

    // Cerrar y volver a abrir no vuelve a pedir lo que ya se tiene.
    await usuario.click(screen.getByRole('button', { name: 'Ocultar detalle' }))
    await usuario.click(screen.getByRole('button', { name: 'Ver detalle' }))
    expect(rutas(espia).filter((ruta) => ruta.includes('vencimientos'))).toHaveLength(1)
  })
})

describe('los estados', () => {
  it('mientras carga lo dice', async () => {
    let soltar
    const espera = new Promise((resolver) => { soltar = resolver })
    await montar({ respuestaPanel: espera.then(() => ({ cuerpo: panelDePrueba() })) })

    expect(screen.getByText('Cargando…')).toBeInTheDocument()
    soltar()
    await esperarPanel()
    expect(screen.queryByText('Cargando…')).not.toBeInTheDocument()
  })

  /** El mensaje del servidor se muestra tal cual: esta escrito para leerse. */
  it('un rechazo del servidor se muestra con su mensaje, sin reescribirlo', async () => {
    const mensaje = 'El rango pedido abarca 400 días y el máximo es 366. Hay que consultar por periodos más cortos.'
    await montar({ respuestaPanel: {
      estado: 400, cuerpo: { codigo: 'PETICION_INVALIDA', error: mensaje },
    } })

    expect(await screen.findByText(mensaje)).toBeInTheDocument()
  })

  it('un servidor caído muestra el aviso de siempre, con reintento', async () => {
    const usuario = userEvent.setup()
    const espia = await montar({ respuestaPanel: {
      estado: 500, cuerpo: { codigo: 'ERROR_INTERNO', error: 'Algo se rompió.' },
    } })

    expect(await screen.findByText('El servidor falló')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    await waitFor(() => expect(delPanel(espia)).toHaveLength(2))
  })

  it('sin red dice que no hay conexión, que no es lo mismo que un servidor caído', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    render(<Metricas />)

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
  })
})

describe('sin rotación', () => {
  async function montarSinRotacion(respuesta = sinRotacionDePrueba()) {
    const espia = fetchFalso({ '/api/v1/metricas/sin-rotacion': { cuerpo: respuesta } })
    vi.stubGlobal('fetch', espia)
    render(<SinRotacion />)
    await screen.findByRole('heading', { name: /sin ventas en los últimos/ })
    return espia
  }

  it('abre con 90 días y muestra las variantes con su valor a costo', async () => {
    const espia = await montarSinRotacion()

    expect(rutas(espia)).toEqual(['/api/v1/metricas/sin-rotacion?dias=90'])
    expect(screen.getByText('Essence|Rubor|Coral')).toBeInTheDocument()
    expect(screen.getByText('$ 72.000')).toBeInTheDocument()
    // 72.000 + 60.000: lo que hay parado, sumado.
    expect(screen.getByText('$ 132.000')).toBeInTheDocument()
  })

  /**
   * "Sin ventas en N dias" NO es "nunca vendido". La aclaracion del servidor llega tal
   * cual, y el titulo dice lo que la lista si afirma.
   */
  it('lleva la aclaración del servidor tal cual: sin ventas no es nunca vendido', async () => {
    const datos = sinRotacionDePrueba()
    await montarSinRotacion(datos)

    expect(screen.getByText(datos.aclaracion)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Con stock y sin ventas en los últimos 90 días' }))
      .toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/nunca vendid[oa]s? en/i)
  })

  it('cambiar los días y pulsar Ver vuelve a pedir con ese número', async () => {
    const usuario = userEvent.setup()
    const espia = await montarSinRotacion()

    const campo = screen.getByLabelText('Días sin ventas')
    await usuario.clear(campo)
    await usuario.type(campo, '30')
    await usuario.click(screen.getByRole('button', { name: 'Ver' }))

    await waitFor(() => expect(rutas(espia).at(-1)).toBe('/api/v1/metricas/sin-rotacion?dias=30'))
    expect(rutas(espia)).toHaveLength(2)
  })

  it('unos días que no son un entero de 1 en adelante no salen al servidor', async () => {
    const usuario = userEvent.setup()
    const espia = await montarSinRotacion()

    const campo = screen.getByLabelText('Días sin ventas')
    await usuario.clear(campo)
    await usuario.type(campo, '0')

    expect(screen.getByText(/entero de días, de 1 en adelante/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ver' })).toBeDisabled()
    expect(rutas(espia)).toHaveLength(1)
  })

  it('si todo rotó, lo dice en positivo', async () => {
    await montarSinRotacion(sinRotacionDePrueba({ filas: [] }))

    expect(screen.getByText(/Todo lo que hay en stock se vendió en los últimos 90 días/))
      .toBeInTheDocument()
  })
})
