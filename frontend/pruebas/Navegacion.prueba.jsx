import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { PERMISOS_DUENA, PERMISOS_EMPLEADA, sesionDe } from './ayudas.jsx'

const contexto = vi.hoisted(() => ({ sesion: null }))

vi.mock('../src/sesion/SesionContext.jsx', async (importarReal) => ({
  ...(await importarReal()),
  useSesion: () => contexto.sesion,
}))

const { Armazon, pestanaInicialDe, seccionesVisibles } = await import('../src/pantallas/Armazon.jsx')

function montar(rol, permisos) {
  contexto.sesion = sesionDe(rol, permisos)
  render(
    <Armazon vista={{ seccion: 'inventario', pestana: 'productos' }} alCambiarVista={() => {}}>
      <p>contenido</p>
    </Armazon>,
  )
}

const navegacion = () => screen.getByRole('navigation', { name: 'Secciones' })
const itemsDeNavegacion = () => within(navegacion()).getAllByRole('button').map((b) => b.textContent)

describe('Navegación', () => {
  it('la DUENA ve la estructura completa, en orden de uso', () => {
    montar('DUENA', PERMISOS_DUENA)

    // El orden importa: Vender primero porque va a ser casi todo el uso, y
    // Metricas al final. Carga inicial NO esta aqui: es una pestaña de
    // Inventario, porque se usa unos dias y despues nunca. Y Catalogo tampoco:
    // dejo de ser una seccion cuando se fusiono con Inventario, que hacia lo mismo.
    expect(itemsDeNavegacion().map((t) => t.replace('pronto', ''))).toEqual([
      'Vender', 'Caja', 'Inventario', 'Compras', 'Métricas',
    ])
  })

  /**
   * ESCONDER NO ES PROTEGER, y por eso el backend responde 403 igual. Pero lo que
   * se esconde tiene que estar de verdad fuera del DOM: una seccion deshabilitada
   * sigue diciendole a la EMPLEADA que existe un modulo de compras y cuanto cuesta
   * la mercancia es justo lo que no le toca.
   */
  it('la EMPLEADA no tiene Compras ni Métricas en el DOM, no solo deshabilitadas', () => {
    montar('EMPLEADA', PERMISOS_EMPLEADA)

    const items = itemsDeNavegacion()
    expect(items.some((texto) => texto.includes('Compras'))).toBe(false)
    expect(items.some((texto) => texto.includes('Métricas'))).toBe(false)

    // Ni siquiera como texto suelto en la pagina.
    expect(within(navegacion()).queryByText(/Compras/)).not.toBeInTheDocument()
    expect(within(navegacion()).queryByText(/Métricas/)).not.toBeInTheDocument()
  })

  it('la EMPLEADA sí ve lo suyo: vender, caja e inventario', () => {
    montar('EMPLEADA', PERMISOS_EMPLEADA)

    expect(itemsDeNavegacion().map((t) => t.replace('pronto', ''))).toEqual([
      'Vender', 'Caja', 'Inventario',
    ])
  })

  /**
   * Una seccion se dibuja si al menos una pestaña sobrevive al filtro. A la
   * EMPLEADA le queda Inventario con Productos sola, sin Ajustes, sin Carga
   * inicial y sin Marcas.
   */
  it('las pestañas se filtran una por una, no la sección entera', () => {
    const dela = seccionesVisibles((permiso) => PERMISOS_EMPLEADA.includes(permiso))
    const inventario = dela.find((s) => s.id === 'inventario')

    expect(inventario.pestanas.map((p) => p.id)).toEqual(['productos'])

    const deLaDuena = seccionesVisibles((permiso) => PERMISOS_DUENA.includes(permiso))
    expect(deLaDuena.find((s) => s.id === 'inventario').pestanas.map((p) => p.id))
      .toEqual(['productos', 'ajustes', 'carga-inicial', 'marcas'])
  })

  /**
   * LA FUSION, AFIRMADA COMO AUSENCIA. Catalogo y Existencias eran dos puertas al
   * mismo sitio: la lista de productos con su stock. Que no vuelvan es la mitad del
   * cambio, y es la mitad que nadie nota al mirar la pantalla nueva.
   */
  it('ya no existen ni la sección Catálogo ni la pestaña Existencias', () => {
    const todas = seccionesVisibles((permiso) => PERMISOS_DUENA.includes(permiso))

    expect(todas.find((s) => s.id === 'catalogo')).toBeUndefined()
    expect(todas.flatMap((s) => s.pestanas ?? []).map((p) => p.id))
      .not.toContain('existencias')
  })

  /** Lo que no existe se declara, pero no se puede pulsar. */
  it('las secciones sin pantalla están deshabilitadas y lo dicen', () => {
    montar('DUENA', PERMISOS_DUENA)

    expect(within(navegacion()).getByRole('button', { name: /Métricas/ })).toBeDisabled()
    expect(within(navegacion()).getByRole('button', { name: /Compras/ })).toBeEnabled()
    // Vender dejó de estar deshabilitada en la Fase 9: ya tiene pantalla.
    expect(within(navegacion()).getByRole('button', { name: /Vender/ })).toBeEnabled()
  })

  /**
   * Una seccion no puede abrir sobre una pestaña que solo dice "llega despues":
   * seria recibir a quien entra con una puerta cerrada.
   */
  it('una sección abre en su primera pestaña que de verdad existe', () => {
    const secciones = seccionesVisibles((permiso) => PERMISOS_DUENA.includes(permiso))

    expect(pestanaInicialDe(secciones.find((s) => s.id === 'inventario'))).toBe('productos')
    expect(pestanaInicialDe(secciones.find((s) => s.id === 'compras'))).toBe('compras')
  })
})
