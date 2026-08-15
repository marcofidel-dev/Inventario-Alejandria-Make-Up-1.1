import { useCallback, useEffect, useMemo, useState } from 'react'

import { catalogo as apiCatalogo } from '../api/endpoints.js'
import { normalizar } from './normalizar.js'

/**
 * El catalogo completo en memoria, y los filtros aplicados en el cliente.
 *
 * Se carga UNA vez y se filtra aqui. No hay una llamada por tecla: eso funciona
 * en desarrollo con tres productos y se cae en el mostrador con el inventario
 * real, justo cuando alguien tiene una clienta esperando. Es tambien lo que dice
 * la convencion del proyecto —cargar el catalogo completo y filtrar en el
 * front— y la razon por la que el endpoint devuelve todo de un tiron en cinco
 * consultas.
 */
export function useCatalogo() {
  const [datos, setDatos] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)

  const recargar = useCallback(async () => {
    setCargando(true)
    setError(null)
    try {
      setDatos(await apiCatalogo.completo())
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
   * Una fila por variante —TODAS, tengan historial o no—, ya unida con su producto,
   * marca y categoria, y con la clave de busqueda calculada una sola vez.
   * Recalcularla en cada tecla seria hacer el mismo trabajo cincuenta veces por
   * palabra escrita.
   */
  const todas = useMemo(() => {
    if (!datos) return []

    const marcas = new Map(datos.marcas.map((m) => [m.id, m]))
    const categorias = new Map(datos.categorias.map((c) => [c.id, c]))
    const productos = new Map(datos.productos.map((p) => [p.id, p]))

    return datos.variantes.map((variante) => {
      const producto = productos.get(variante.productoId)
      const marca = producto ? marcas.get(producto.marcaId) : undefined
      const categoria = producto ? categorias.get(producto.categoriaId) : undefined

      return {
        ...variante,
        productoNombre: producto?.nombre ?? '',
        marcaId: producto?.marcaId ?? null,
        marcaNombre: marca?.nombre ?? '',
        categoriaId: producto?.categoriaId ?? null,
        categoriaNombre: categoria?.nombre ?? '',
        // stock bajo es stock < stockMinimo, el mismo criterio que
        // VarianteRepository.bajoMinimo(): si la pantalla y el backend usaran
        // criterios distintos, la lista y la alerta se contradirian.
        stockBajo: variante.stock < variante.stockMinimo,
        // Negativo no es lo mismo que bajo, aunque un negativo casi siempre sea
        // tambien bajo. Bajo dice "hay que reponer"; negativo dice "se vendio algo
        // que no habia", que es un descuadre y no una compra pendiente. Aparece al
        // anular una compra cuya mercancia ya se habia vendido.
        stockNegativo: variante.stock < 0,
        clave: normalizar(
          [marca?.nombre, producto?.nombre, variante.tono, variante.tamano, variante.codigoBarras]
            .filter(Boolean)
            .join(' '),
        ),
      }
    })
  }, [datos])

  /**
   * Las variantes que de verdad existen: las que tienen al menos un movimiento.
   *
   * Una variante sin historial es un registro sobre nada — se creo dentro de un
   * borrador de compra que todavia no llego, o de uno que se descarto. No se lista
   * en el catalogo ni se puede vender: sin costo real, la venta congelaria costo 0
   * y el margen historico queda corrompido para siempre.
   *
   * ESTA es la lista por defecto, y la que tiene que usar el buscador del POS. La
   * completa (`todas`) es la excepcion, y solo para las dos pantallas por donde
   * entra la mercancia y para resolver el nombre de una variante ya referenciada
   * por una compra.
   */
  const filas = useMemo(() => todas.filter((fila) => fila.conHistorial), [todas])

  return {
    cargando,
    error,
    recargar,
    marcas: datos?.marcas ?? [],
    categorias: datos?.categorias ?? [],
    productos: datos?.productos ?? [],
    filas,
    todas,
    estaVacio: Boolean(datos) && filas.length === 0,
  }
}

/** Aplica los filtros sobre las filas ya calculadas. Funcion pura, testeable sola. */
export function filtrar(filas, { texto = '', marcaId = '', categoriaId = '', soloStockBajo = false,
  incluirInactivos = false } = {}) {
  const termino = normalizar(texto)

  return filas.filter((fila) => {
    if (!incluirInactivos && !fila.activo) return false
    if (termino && !fila.clave.includes(termino)) return false
    if (marcaId && String(fila.marcaId) !== String(marcaId)) return false
    if (categoriaId && String(fila.categoriaId) !== String(categoriaId)) return false
    if (soloStockBajo && !fila.stockBajo) return false
    return true
  })
}
