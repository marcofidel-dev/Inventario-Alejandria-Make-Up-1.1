import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { BuscadorDeVariante, descripcionDe } from '../src/componentes/BuscadorDeVariante.jsx'
import { normalizar } from '../src/catalogo/normalizar.js'

/**
 * Ocho tonos del mismo labial. Es el caso real de una tienda de maquillaje —un
 * producto tiene diez o quince tonos— y es justo el que llenaba el desplegable.
 */
const TONOS = ['Rojo carmín', 'Nude', 'Coral', 'Vino', 'Rosa viejo', 'Terracota',
  'Fucsia', 'Durazno']

const FILAS = TONOS.map((tono, i) => ({
  id: 1000 + i,
  activo: true,
  conHistorial: true,
  marcaId: 1,
  categoriaId: 10,
  stock: 5,
  stockMinimo: 0,
  precioVenta: 32000,
  tono,
  tamano: null,
  // Como la manda el backend: la arma Descripcion.de() y el front no la reconstruye.
  descripcion: `Loréal|Labial mate|${tono}`,
  // Con la misma funcion que usa el catalogo: escribirla a mano se desincroniza del
  // filtro y la prueba dejaria de encontrar lo que el mostrador si encuentra.
  clave: normalizar(`Loréal Labial mate ${tono}`),
}))

function montar(props = {}) {
  const alElegir = vi.fn()
  render(<BuscadorDeVariante filas={FILAS} valor="" alElegir={alElegir} indice={0} {...props} />)
  return { alElegir }
}

const lista = () => screen.queryByRole('listbox')

describe('el desplegable de sugerencias', () => {
  /**
   * SEIS COMO MINIMO, Y ANTES SE VEIAN DOS.
   *
   * La lista se pintaba dentro de la envoltura de la tabla, que tiene scroll propio:
   * el primer ancestro con `overflow` distinto de `visible` recorta a un hijo
   * posicionado, asi que el desplegable quedaba cortado a media fila. Con quince tonos
   * del mismo labial, ver dos sugerencias no es buscar.
   */
  it('muestra las ocho coincidencias, no las dos que cabian en la caja', async () => {
    const usuario = userEvent.setup()
    montar()

    await usuario.type(screen.getByRole('combobox'), 'labial')

    const opciones = within(lista()).getAllByRole('option')
    expect(opciones.length).toBeGreaterThanOrEqual(6)
    expect(opciones).toHaveLength(8)
  })

  /**
   * LA LISTA SE POSICIONA SOLA, EN COORDENADAS DE VENTANA.
   *
   * Es lo que la saca de cualquier contenedor que pueda recortarla. Si alguien la
   * devuelve a `position: absolute` y borra la medicion, el desplegable vuelve a
   * quedar preso de la envoltura de la tabla — y eso no se nota en ninguna prueba que
   * solo mire que las opciones existan, porque existir existen: no se ven.
   */
  it('se ancla al campo con coordenadas propias, no al contenedor', async () => {
    const usuario = userEvent.setup()
    montar()

    const campo = screen.getByRole('combobox')
    vi.spyOn(campo, 'getBoundingClientRect').mockReturnValue({
      left: 120, top: 200, bottom: 232, right: 420, width: 300, height: 32, x: 120, y: 200,
    })

    await usuario.type(campo, 'labial')

    // `position: fixed` lo pone la hoja de estilos, que jsdom no carga. Lo que se
    // afirma aqui es lo unico que puede afirmarse y ademas lo unico que se puede
    // borrar por descuido: que el componente calcula las coordenadas.
    expect(lista()).toHaveStyle({ left: '120px', width: '300px', top: '232px' })
  })

  /**
   * ANCHA COMO LA DESCRIPCION, NO COMO LA CELDA.
   *
   * El campo vive en la columna "Variante" de la tabla de captura y mide unos 280px.
   * Una descripcion completa —"Maybelline|Labial mate Superstay|Rojo clásico|5 ml"—
   * no cabe, asi que cada opcion se partia en dos lineas: ocho opciones de dos lineas
   * son 416px de contenido, y ahi ya no entran en ninguna altura razonable. Con el
   * ancho minimo puesto, una opcion es una linea.
   *
   * El minimo se lee del estilo calculado a proposito: asi el numero vive en
   * `tokens.css` y no escrito dentro del JavaScript, que es lo que exige el sistema.
   */
  it('es al menos tan ancha como su min-width, aunque el campo sea angosto', async () => {
    const usuario = userEvent.setup()
    montar()

    const original = window.getComputedStyle.bind(window)
    vi.spyOn(window, 'getComputedStyle').mockImplementation((elemento) => (
      elemento.classList?.contains('buscador__lista')
        ? { minWidth: '416px' }
        : original(elemento)
    ))

    const campo = screen.getByRole('combobox')
    vi.spyOn(campo, 'getBoundingClientRect').mockReturnValue({
      left: 260, top: 200, bottom: 232, right: 541, width: 281, height: 32, x: 260, y: 200,
    })

    await usuario.type(campo, 'labial')

    // Se mira el estilo en linea y no el calculado: getComputedStyle esta suplantado
    // en esta prueba, que es justamente de donde el componente saca el minimo.
    expect(lista().style.width).toBe('416px')
  })

  /**
   * LA ALTURA SALE DEL ESPACIO QUE HAY, NO DE UNA CUENTA DE FILAS.
   *
   * Era `alto-de-fila x 8`, que da 288px y da por hecho que cada opcion ocupa una
   * linea. Con las que ocupaban dos, la lista se quedaba con scroll propio mostrando
   * cinco y media — con 438px libres justo debajo. Una cuenta de filas no puede saber
   * cuanto mide una opcion; el hueco de la pantalla si se puede medir.
   */
  it('la altura máxima es el espacio libre, no una cuenta de filas', async () => {
    const usuario = userEvent.setup()
    montar()

    const campo = screen.getByRole('combobox')
    vi.spyOn(campo, 'getBoundingClientRect').mockReturnValue({
      left: 120, top: 200, bottom: 232, right: 420, width: 300, height: 32, x: 120, y: 200,
    })

    await usuario.type(campo, 'labial')

    // Todo lo que queda bajo el campo menos la holgura del borde.
    expect(lista()).toHaveStyle({ maxHeight: `${window.innerHeight - 232 - 8}px` })
  })

  /**
   * Hacia el lado donde hay mas sitio. En una factura de cuarenta renglones las
   * ultimas lineas estan siempre abajo, y hacia abajo no cabrian ni dos sugerencias
   * por mucho que ya nada las recorte.
   */
  it('abre hacia arriba cuando abajo no queda espacio', async () => {
    const usuario = userEvent.setup()
    montar()

    const campo = screen.getByRole('combobox')
    const bajo = window.innerHeight - 40
    vi.spyOn(campo, 'getBoundingClientRect').mockReturnValue({
      left: 120, top: bajo, bottom: bajo + 32, right: 420, width: 300, height: 32,
      x: 120, y: bajo,
    })

    await usuario.type(campo, 'labial')

    // Anclada por abajo al borde superior del campo: crece hacia arriba.
    expect(lista()).toHaveStyle({ bottom: `${window.innerHeight - bajo}px` })
    expect(lista().style.top).toBe('')
  })
})

describe('cómo se nombra una variante', () => {
  /**
   * UNA SOLA CADENA PARA TODA LA APLICACION, Y LA ARMA EL BACKEND.
   *
   * El buscador tenia su propia version con punto medio —"Montoc · Polvos sueltos"—
   * mientras el recibo de esa misma venta imprimia "Montoc|Polvos sueltos". Dos
   * implementaciones de la misma cadena divergen siempre; aqui ya lo habian hecho y
   * nadie lo noto hasta tener el papel en la mano. `Descripcion.de()` la arma y el
   * front la muestra: por eso esta prueba compara contra el campo del DTO, no contra
   * un formato escrito otra vez aqui.
   */
  it('muestra la descripción del backend tal cual, sin rearmarla', async () => {
    const usuario = userEvent.setup()
    montar()

    await usuario.type(screen.getByRole('combobox'), 'carmín')

    const opcion = within(lista()).getByRole('option')
    expect(opcion).toHaveTextContent(FILAS[0].descripcion)
    expect(descripcionDe(FILAS[0])).toBe(FILAS[0].descripcion)
  })

  /** Una fila sin descripción no puede dejar la línea en blanco. */
  it('una fila sin descripción no deja la línea vacía', () => {
    expect(descripcionDe({ id: 1 })).toBe('')
  })
})
