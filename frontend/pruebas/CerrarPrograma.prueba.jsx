import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { fetchFalso } from './ayudas.jsx'
import { BotonCerrarPrograma } from '../src/pantallas/CerrarPrograma.jsx'

function montar(respuestaDeApagado) {
  const espia = fetchFalso({
    '/api/v1/sistema/apagado': respuestaDeApagado ?? { cuerpo: { exitoso: true, motivo: null } },
  })
  vi.stubGlobal('fetch', espia)
  const alApagado = vi.fn()
  render(<BotonCerrarPrograma alApagado={alApagado} />)
  return { espia, alApagado }
}

function envios(espia) {
  return espia.mock.calls.filter(([ruta]) => ruta === '/api/v1/sistema/apagado')
}

describe('Cerrar programa', () => {
  it('el camino feliz confirma, pide el apagado con forzar:false y avisa', async () => {
    const usuario = userEvent.setup()
    const { espia, alApagado } = montar()

    await usuario.click(screen.getByRole('button', { name: 'Cerrar programa' }))
    await usuario.click(screen.getByRole('button', { name: 'Sí, cerrar el programa' }))

    expect(alApagado).toHaveBeenCalledTimes(1)
    const [, opciones] = envios(espia)[0]
    expect(JSON.parse(opciones.body)).toEqual({ forzar: false })
  })

  /**
   * El backend no apaga si el respaldo falla: dice el motivo tal cual y
   * pregunta si cerrar de todas formas. "de todas formas" manda forzar:true,
   * y no vuelve a preguntar lo mismo.
   */
  it('si el respaldo falla, muestra el motivo y "de todas formas" fuerza el apagado', async () => {
    const usuario = userEvent.setup()
    const { espia, alApagado } = montar({
      cuerpo: { exitoso: false, motivo: 'No se pudo generar el respaldo en /backups/backup.db' },
    })

    await usuario.click(screen.getByRole('button', { name: 'Cerrar programa' }))
    await usuario.click(screen.getByRole('button', { name: 'Sí, cerrar el programa' }))

    expect(await screen.findByText('No se pudo generar el respaldo en /backups/backup.db'))
      .toBeInTheDocument()
    expect(alApagado).not.toHaveBeenCalled()

    // Antes de forzar, el backend ya respondió que no podía apagar: fetchFalso
    // de aquí en adelante responde distinto, como lo haría el servidor con
    // forzar:true.
    espia.mockImplementationOnce(async (ruta, opciones) => {
      const cuerpo = JSON.parse(opciones.body)
      expect(cuerpo).toEqual({ forzar: true })
      return { ok: true, status: 200, text: async () => JSON.stringify({ exitoso: true, motivo: null }) }
    })
    await usuario.click(screen.getByRole('button', { name: 'Cerrar de todas formas' }))

    expect(alApagado).toHaveBeenCalledTimes(1)
  })

  it('cancelar en el primer paso no pide nada al servidor', async () => {
    const usuario = userEvent.setup()
    const { espia, alApagado } = montar()

    await usuario.click(screen.getByRole('button', { name: 'Cerrar programa' }))
    await usuario.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(envios(espia)).toHaveLength(0)
    expect(alApagado).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('un fallo de red se avisa y no llama a alApagado', async () => {
    const usuario = userEvent.setup()
    const espia = vi.fn(async () => { throw new TypeError('network error') })
    vi.stubGlobal('fetch', espia)
    const alApagado = vi.fn()
    render(<BotonCerrarPrograma alApagado={alApagado} />)

    await usuario.click(screen.getByRole('button', { name: 'Cerrar programa' }))
    await usuario.click(screen.getByRole('button', { name: 'Sí, cerrar el programa' }))

    expect(await screen.findByText('No hay conexión con el servidor')).toBeInTheDocument()
    expect(alApagado).not.toHaveBeenCalled()
  })
})
