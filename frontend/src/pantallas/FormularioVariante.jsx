import { useState } from 'react'

import { CODIGOS } from '../api/cliente.js'
import { catalogo as apiCatalogo } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'
import { detalleDe } from './FormularioProducto.jsx'

/**
 * Alta y edicion de variante.
 *
 * SIN CAMPO DE COSTO PROMEDIO, y no por olvido: el costo se deriva de la carga
 * inicial y de la recepcion de compras, y el backend ni lo acepta en el cuerpo. Un
 * campo aqui prometeria algo que no pasa.
 */
export function FormularioVariante({ catalogo, variante, productoInicial, alCerrar, alGuardar }) {
  const [productoId, setProductoId] = useState(
    String(variante?.productoId ?? productoInicial?.id ?? ''),
  )
  const [tono, setTono] = useState(variante?.tono ?? '')
  const [tamano, setTamano] = useState(variante?.tamano ?? '')
  const [codigoBarras, setCodigoBarras] = useState(variante?.codigoBarras ?? '')
  const [precioVenta, setPrecioVenta] = useState(
    variante ? String(variante.precioVenta) : '',
  )
  const [stockMinimo, setStockMinimo] = useState(
    variante ? String(variante.stockMinimo) : '0',
  )
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const editando = Boolean(variante)
  const precio = Number(precioVenta)
  // Una variante activa necesita precio mayor que cero: con precio 0 se venderia
  // gratis en el mostrador. El backend lo impone; avisarlo antes de enviar evita
  // un viaje de ida y vuelta para algo que ya se sabe.
  const precioInvalido = precioVenta !== '' && (!Number.isFinite(precio) || precio <= 0)
  const puedeGuardar = productoId && precioVenta !== '' && !precioInvalido && !guardando

  async function guardar() {
    if (!puedeGuardar) return
    setGuardando(true)
    setError(null)
    const datos = {
      productoId: Number(productoId),
      tono: tono.trim() || null,
      tamano: tamano.trim() || null,
      codigoBarras: codigoBarras.trim() || null,
      precioVenta: precio,
      stockMinimo: Number(stockMinimo) || 0,
    }
    try {
      const guardada = editando
        ? await apiCatalogo.actualizarVariante(variante.id, datos)
        : await apiCatalogo.crearVariante(datos)
      await alGuardar(guardada)
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  const esCombinacionRepetida = error?.codigo === CODIGOS.unicidadViolada

  return (
    <Modal
      titulo={editando ? 'Editar variante' : 'Nueva variante'}
      alCerrar={alCerrar}
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={guardando}>Cancelar</Boton>
          <Boton variante="principal" onClick={guardar} ocupado={guardando}
                 disabled={!puedeGuardar}>
            {editando ? 'Guardar' : 'Crear variante'}
          </Boton>
        </>
      }
    >
      <div className="formulario">
        <Campo etiqueta="Producto" error={detalleDe(error, 'productoId')}>
          {(props) => (
            <select {...props} value={productoId} disabled={editando}
                    onChange={(e) => setProductoId(e.target.value)}>
              <option value="">Elegir…</option>
              {catalogo.productos.map((producto) => (
                <option key={producto.id} value={producto.id}>
                  {nombreCompleto(catalogo, producto)}
                </option>
              ))}
            </select>
          )}
        </Campo>

        <div className="formulario__pareja">
          <Campo etiqueta="Tono" value={tono} autoFocus={!editando}
                 error={detalleDe(error, 'tono')}
                 onChange={(e) => setTono(e.target.value)} />
          <Campo etiqueta="Tamaño" value={tamano} error={detalleDe(error, 'tamano')}
                 onChange={(e) => setTamano(e.target.value)} />
        </div>

        <div className="formulario__pareja">
          <Campo
            etiqueta="Precio de venta"
            inputMode="numeric"
            value={precioVenta}
            error={precioInvalido
              ? 'Una variante activa necesita un precio mayor que cero.'
              : detalleDe(error, 'precioVenta')}
            onChange={(e) => setPrecioVenta(e.target.value.replace(/\D/g, ''))}
          />
          <Campo
            etiqueta="Stock mínimo"
            inputMode="numeric"
            value={stockMinimo}
            ayuda="Debajo de este número se marca como stock bajo."
            error={detalleDe(error, 'stockMinimo')}
            onChange={(e) => setStockMinimo(e.target.value.replace(/\D/g, ''))}
          />
        </div>

        <Campo etiqueta="Código de barras (opcional)" value={codigoBarras}
               error={detalleDe(error, 'codigoBarras')}
               onChange={(e) => setCodigoBarras(e.target.value)} />

        {esCombinacionRepetida && <Aviso tipo="error">{error.message}</Aviso>}

        {error && !esCombinacionRepetida && error.codigo !== CODIGOS.validacionFallida && (
          <AvisoDeError error={error} />
        )}
      </div>
    </Modal>
  )
}

export function nombreCompleto(catalogo, producto) {
  const marca = catalogo.marcas.find((m) => m.id === producto.marcaId)
  return marca ? `${marca.nombre} · ${producto.nombre}` : producto.nombre
}
