import { useCallback, useEffect, useMemo, useState } from 'react'

import { compras as apiCompras, proveedores as apiProveedores } from '../api/endpoints.js'

/** Los estados en el orden en que exigen atencion, no en el que ocurrieron. */
const ORDEN_DE_ESTADO = { BORRADOR: 0, RECIBIDA: 1, ANULADA: 2, DESCARTADA: 3 }

/**
 * Compras y proveedores en memoria.
 *
 * Se cargan completos y se filtran aqui, igual que el catalogo: es la convencion
 * del proyecto y en una tienda caben de sobra. Los dos viven en el mismo hook
 * porque no se usan por separado — una compra sin su proveedor no se puede ni
 * pintar.
 */
export function useCompras() {
  const [lista, setLista] = useState(null)
  const [listaProveedores, setListaProveedores] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)

  const recargar = useCallback(async () => {
    setCargando(true)
    setError(null)
    try {
      const [pedidas, suyos] = await Promise.all([
        apiCompras.listar(),
        apiProveedores.listar(),
      ])
      setLista(pedidas)
      setListaProveedores(suyos)
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCargando(false)
    }
  }, [])

  useEffect(() => {
    recargar()
  }, [recargar])

  /**
   * Las compras con su proveedor resuelto y en el orden en que hay que mirarlas:
   * LOS BORRADORES PRIMERO. Un borrador es mercancia que puede estar en el piso sin
   * registrar, asi que es lo unico de esta lista que exige hacer algo hoy; el resto
   * es historia. Dentro de cada grupo, lo mas reciente arriba.
   */
  const filas = useMemo(() => {
    if (!lista) return []
    const porId = new Map((listaProveedores ?? []).map((p) => [p.id, p]))

    return [...lista]
      .map((compra) => ({
        ...compra,
        proveedorNombre: porId.get(compra.proveedorId)?.nombre ?? '—',
      }))
      .sort((una, otra) => {
        const porEstado = ORDEN_DE_ESTADO[una.estado] - ORDEN_DE_ESTADO[otra.estado]
        return porEstado !== 0 ? porEstado : otra.fecha.localeCompare(una.fecha)
      })
  }, [lista, listaProveedores])

  const borradores = useMemo(
    () => filas.filter((compra) => compra.estado === 'BORRADOR').length,
    [filas],
  )

  return {
    cargando,
    error,
    recargar,
    filas,
    borradores,
    proveedores: listaProveedores ?? [],
    proveedoresActivos: (listaProveedores ?? []).filter((p) => p.activo),
    sinProveedores: Boolean(listaProveedores) && listaProveedores.length === 0,
  }
}

/** Filtros del listado, aplicados en memoria. Funcion pura, testeable sola. */
export function filtrarCompras(filas, { estado = '', proveedorId = '' } = {}) {
  return filas.filter((compra) => {
    if (estado && compra.estado !== estado) return false
    if (proveedorId && String(compra.proveedorId) !== String(proveedorId)) return false
    return true
  })
}
