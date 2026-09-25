import { useCallback, useState } from 'react'

import { ventas as apiVentas } from '../api/endpoints.js'

/**
 * Abrir y regenerar el recibo de una venta.
 *
 * Vive aparte porque lo usan los dos sitios donde hace falta y por razones
 * distintas: el mostrador justo despues de cobrar —la clienta esta enfrente y quiere
 * el papel— y el listado del dia, para reimprimir o para recuperar el comprobante de
 * una venta que se quedo sin el.
 *
 * NINGUN FALLO DE AQUI TUMBA NADA. El recibo es derivado: la venta ya esta cobrada y
 * la plata ya entro. Si no se puede abrir el PDF —no hay visor asociado, alguien
 * movio la carpeta— se dice y se sigue. Por eso el error se guarda para mostrarlo y
 * no se relanza.
 */
export function useRecibo() {
  const [error, setError] = useState(null)
  const [ocupada, setOcupada] = useState(null)

  const ejecutar = useCallback(async (ventaId, accion) => {
    setError(null)
    setOcupada(ventaId)
    try {
      return await accion(ventaId)
    } catch (fallo) {
      // El mensaje del backend dice donde quedo el archivo cuando no hay visor, y eso
      // es exactamente lo que hace falta para abrirlo a mano. Reescribirlo lo perderia.
      setError(fallo.message)
      return null
    } finally {
      setOcupada(null)
    }
  }, [])

  const abrir = useCallback(
    (ventaId) => ejecutar(ventaId, apiVentas.abrirRecibo),
    [ejecutar],
  )

  /** Devuelve la venta actualizada —ya con su ruta— o null si no se pudo. */
  const generar = useCallback(
    (ventaId) => ejecutar(ventaId, apiVentas.generarRecibo),
    [ejecutar],
  )

  return { abrir, generar, error, ocupada, limpiarError: () => setError(null) }
}
