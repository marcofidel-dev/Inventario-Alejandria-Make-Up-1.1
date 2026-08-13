import { useCallback, useRef, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'

import { CODIGOS } from '../api/cliente.js'
import { inventario } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { formatearPesos } from './Catalogo.jsx'

/**
 * Carga inicial de existencias: la pantalla con la que se va a meter el inventario
 * real de la tienda, durante horas.
 *
 * TODO ESTA DISEÑADO PARA NO TOCAR EL RATON. El foco se encadena solo —variante,
 * cantidad, costo— y Enter en el costo agrega una linea nueva con el foco ya
 * puesto en su primer campo. Quien esta copiando cien productos de una lista no
 * puede estar alternando entre teclado y raton en cada uno: eso no es incomodidad,
 * son horas.
 *
 * El lote se envia completo al final porque el backend es todo-o-nada. Si una
 * linea falla, se marca ESA linea y no se pierde el trabajo: volver a teclear
 * cuarenta lineas por un error en la treinta y ocho seria imperdonable.
 */
export function CargaInicial({ catalogo }) {
  const [lineas, setLineas] = useState([lineaVacia()])
  const [error, setError] = useState(null)
  const [resultado, setResultado] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const contenedor = useRef(null)

  const variantesSinCarga = catalogo.filas

  const actualizar = useCallback((indice, cambios) => {
    setLineas((actuales) => actuales.map((linea, i) => (
      i === indice ? { ...linea, ...cambios, error: undefined } : linea
    )))
  }, [])

  const agregarLinea = useCallback(() => {
    setLineas((actuales) => [...actuales, lineaVacia()])
    // El foco al primer campo de la linea nueva, en el ciclo siguiente para que
    // ya exista en el DOM.
    requestAnimationFrame(() => {
      const selects = contenedor.current?.querySelectorAll('select[data-variante]')
      selects?.[selects.length - 1]?.focus()
    })
  }, [])

  function alTeclearEnCosto(evento, indice) {
    if (evento.key !== 'Enter') return
    evento.preventDefault()
    if (indice === lineas.length - 1) agregarLinea()
    else {
      const selects = contenedor.current?.querySelectorAll('select[data-variante]')
      selects?.[indice + 1]?.focus()
    }
  }

  const completas = lineas.filter((linea) => linea.varianteId && linea.cantidad && linea.costoUnitario)

  async function enviar() {
    if (completas.length === 0 || enviando) return
    setEnviando(true)
    setError(null)
    setResultado(null)
    try {
      const respuesta = await inventario.cargaInicial(completas.map((linea) => ({
        varianteId: Number(linea.varianteId),
        cantidad: Number(linea.cantidad),
        costoUnitario: Number(linea.costoUnitario),
      })))
      setResultado(respuesta)
      setLineas([lineaVacia()])
      await catalogo.recargar()
    } catch (fallo) {
      setError(fallo)
      marcarLineaCulpable(fallo, setLineas)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Carga inicial de existencias</h1>
      </div>

      <Aviso tipo="info">
        Cada variante admite una sola carga inicial. Lo que venga después se registra
        como ajuste de inventario, que deja constancia del motivo.
      </Aviso>

      {resultado && (
        <Aviso tipo="exito" titulo={`Se cargaron ${resultado.variantesCargadas} variante(s)`}>
          Ya aparecen con su existencia en el catálogo.
        </Aviso>
      )}

      {error && error.codigo !== CODIGOS.cargaInicialYaRegistrada && (
        <AvisoDeError error={error} />
      )}

      <div className="tabla-envoltura" ref={contenedor}>
        <table className="tabla carga__tabla">
          <thead>
            <tr>
              <th>Variante</th>
              <th className="numero">Cantidad</th>
              <th className="numero">Costo unitario</th>
              <th className="numero">Total</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lineas.map((linea, indice) => (
              <tr key={linea.clave}>
                <td>
                  <Campo etiqueta="" error={linea.error}>
                    {(props) => (
                      <select
                        {...props}
                        data-variante="true"
                        value={linea.varianteId}
                        aria-label={`Variante de la línea ${indice + 1}`}
                        onChange={(e) => actualizar(indice, { varianteId: e.target.value })}
                      >
                        <option value="">Elegir variante…</option>
                        {variantesSinCarga.map((fila) => (
                          <option key={fila.id} value={fila.id}>
                            {[fila.marcaNombre, fila.productoNombre, fila.tono, fila.tamano]
                              .filter(Boolean).join(' · ')}
                          </option>
                        ))}
                      </select>
                    )}
                  </Campo>
                </td>
                <td className="carga__celda-numero">
                  <Campo etiqueta="">
                    {(props) => (
                      <input
                        {...props}
                        inputMode="numeric"
                        value={linea.cantidad}
                        aria-label={`Cantidad de la línea ${indice + 1}`}
                        onChange={(e) => actualizar(indice, {
                          cantidad: e.target.value.replace(/\D/g, ''),
                        })}
                      />
                    )}
                  </Campo>
                </td>
                <td className="carga__celda-numero">
                  <Campo etiqueta="">
                    {(props) => (
                      <input
                        {...props}
                        inputMode="numeric"
                        value={linea.costoUnitario}
                        aria-label={`Costo unitario de la línea ${indice + 1}`}
                        onChange={(e) => actualizar(indice, {
                          costoUnitario: e.target.value.replace(/\D/g, ''),
                        })}
                        onKeyDown={(e) => alTeclearEnCosto(e, indice)}
                      />
                    )}
                  </Campo>
                </td>
                <td className="numero monto">
                  {linea.cantidad && linea.costoUnitario
                    ? formatearPesos(Number(linea.cantidad) * Number(linea.costoUnitario))
                    : '—'}
                </td>
                <td>
                  <Boton
                    variante="plano"
                    icono={Trash2}
                    aria-label={`Quitar la línea ${indice + 1}`}
                    disabled={lineas.length === 1}
                    onClick={() => setLineas((a) => a.filter((_, i) => i !== indice))}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="carga__pie">
        <div>
          <Boton icono={Plus} onClick={agregarLinea}>Agregar línea</Boton>
          <p className="carga__pista">
            Tab pasa al campo siguiente. Enter en el costo agrega otra línea.
          </p>
        </div>
        <Boton variante="principal" onClick={enviar} ocupado={enviando}
               disabled={completas.length === 0} textoOcupado="Cargando…">
          Cargar {completas.length} línea(s)
        </Boton>
      </div>
    </>
  )
}

let siguienteClave = 0

function lineaVacia() {
  siguienteClave += 1
  return { clave: siguienteClave, varianteId: '', cantidad: '', costoUnitario: '' }
}

/**
 * El backend nombra la variante que rompio el lote. Se marca esa linea para que
 * quien tiene cuarenta cargadas sepa cual arreglar sin leer un parrafo.
 */
function marcarLineaCulpable(fallo, setLineas) {
  if (fallo.codigo !== CODIGOS.cargaInicialYaRegistrada
      && fallo.codigo !== CODIGOS.peticionInvalida) return

  const encontrado = /variante (\d+)/i.exec(fallo.message)
  if (!encontrado) return
  const idCulpable = encontrado[1]

  setLineas((actuales) => actuales.map((linea) => (
    String(linea.varianteId) === idCulpable ? { ...linea, error: fallo.message } : linea
  )))
}
