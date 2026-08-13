import { useState } from 'react'

import { inventario } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { detalleDe } from './FormularioProducto.jsx'

/**
 * Ajuste de inventario. Solo DUENA, y el motivo es obligatorio.
 *
 * La cantidad va con signo: positiva si sobra, negativa si falta. Se piden por
 * separado la direccion y la cantidad en vez de un campo con menos, porque teclear
 * "-3" y que se pierda el guion es un ajuste al reves — y un ajuste al reves no se
 * corrige borrandolo, se corrige con otro ajuste que queda en el historial.
 */
export function AjusteInventario({ catalogo }) {
  const [varianteId, setVarianteId] = useState('')
  const [direccion, setDireccion] = useState('faltan')
  const [cantidad, setCantidad] = useState('')
  const [motivo, setMotivo] = useState('')
  const [error, setError] = useState(null)
  const [resultado, setResultado] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const puedeEnviar = varianteId && Number(cantidad) > 0 && motivo.trim() && !enviando
  const fila = catalogo.filas.find((f) => String(f.id) === String(varianteId))

  async function enviar() {
    if (!puedeEnviar) return
    setEnviando(true)
    setError(null)
    setResultado(null)
    try {
      const respuesta = await inventario.ajustar({
        varianteId: Number(varianteId),
        cantidad: direccion === 'faltan' ? -Number(cantidad) : Number(cantidad),
        motivo: motivo.trim(),
      })
      setResultado(respuesta)
      setCantidad('')
      setMotivo('')
      await catalogo.recargar()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Ajuste de inventario</h1>
      </div>

      <Aviso tipo="info">
        Un ajuste no borra nada: agrega un movimiento al historial con su motivo. Para
        registrar existencias por primera vez está la carga inicial.
      </Aviso>

      {resultado && (
        <Aviso tipo="exito" titulo="Ajuste registrado">
          La existencia quedó en {resultado.stockResultante}.
        </Aviso>
      )}

      <div className="formulario formulario--angosto">
        <Campo etiqueta="Variante" error={detalleDe(error, 'varianteId')}>
          {(props) => (
            <select {...props} value={varianteId} autoFocus
                    onChange={(e) => setVarianteId(e.target.value)}>
              <option value="">Elegir variante…</option>
              {catalogo.filas.map((f) => (
                <option key={f.id} value={f.id}>
                  {[f.marcaNombre, f.productoNombre, f.tono, f.tamano].filter(Boolean).join(' · ')}
                  {` — existencia ${f.stock}`}
                </option>
              ))}
            </select>
          )}
        </Campo>

        <div className="formulario__pareja">
          <Campo etiqueta="Qué pasó">
            {(props) => (
              <select {...props} value={direccion} onChange={(e) => setDireccion(e.target.value)}>
                <option value="faltan">Faltan unidades</option>
                <option value="sobran">Sobran unidades</option>
              </select>
            )}
          </Campo>
          <Campo
            etiqueta="Cuántas"
            inputMode="numeric"
            value={cantidad}
            error={detalleDe(error, 'cantidad')}
            onChange={(e) => setCantidad(e.target.value.replace(/\D/g, ''))}
          />
        </div>

        <Campo
          etiqueta="Motivo"
          value={motivo}
          ayuda="Obligatorio: en tres meses este texto es lo único que explica el ajuste."
          error={detalleDe(error, 'motivo')}
          onChange={(e) => setMotivo(e.target.value)}
        />

        {fila && cantidad && (
          <p className="texto-secundario">
            {fila.stock} → <span className="monto">
              {direccion === 'faltan' ? fila.stock - Number(cantidad) : fila.stock + Number(cantidad)}
            </span>
          </p>
        )}

        {error && <AvisoDeError error={error} />}

        <div>
          <Boton variante="principal" onClick={enviar} ocupado={enviando}
                 disabled={!puedeEnviar} textoOcupado="Registrando…">
            Registrar ajuste
          </Boton>
        </div>
      </div>
    </>
  )
}
