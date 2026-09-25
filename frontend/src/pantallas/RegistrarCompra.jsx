import { useCallback, useRef, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'

import { CODIGOS } from '../api/cliente.js'
import { compras as apiCompras, proveedores as apiProveedores } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { BuscadorDeVariante } from '../componentes/BuscadorDeVariante.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { SelectorConAlta } from '../componentes/SelectorConAlta.jsx'
import { formatearPesos } from './Catalogo.jsx'
import { FormularioProducto } from './FormularioProducto.jsx'
import { FormularioVariante } from './FormularioVariante.jsx'

/**
 * Registrar una compra: copiar una factura de proveedor, linea por linea.
 *
 * DISEÑADA PARA NO TOCAR EL RATON, igual que la carga inicial. El foco se encadena
 * variante -> cantidad -> costo, y Enter en el costo agrega una linea nueva con el
 * foco ya puesto en su primer campo. Una factura de proveedor trae cuarenta
 * renglones y quien los copia no puede estar alternando entre teclado y raton en
 * cada uno: eso no es incomodidad, son horas.
 *
 * EL TOTAL DE ESTA PANTALLA ES SOLO PARA ORIENTAR. El que vale lo calcula el
 * servidor sumando las lineas, y es el que se muestra despues de guardar. No es
 * desconfianza del front: es que el total guardado tiene que salir de una sola
 * cabeza, y una pantalla puede tener un redondeo distinto o quedarse a medio
 * actualizar.
 *
 * Si el producto de la factura no existe todavia se crea desde aqui, pero CON EL
 * FORMULARIO COMPLETO —producto y despues variante, los dos encadenados— y no con
 * un atajo. Un alta rapida a mitad de una captura de cuarenta lineas es la forma
 * segura de acabar con un catalogo lleno de registros a medias que nadie vuelve a
 * mirar.
 *
 * CON `compraInicial` esta misma pantalla EDITA un borrador que ya existe en vez de
 * crear uno —es el paso que sigue a "Corregir" una compra recibida: el borrador de
 * reemplazo ya quedo creado en el servidor con las mismas lineas, y aqui se abre para
 * tocarlo antes de volver a recibir—. Guardar entonces manda PUT, no POST.
 */
export function RegistrarCompra({ catalogo, compras, compraInicial, alCerrar, alRecibir }) {
  const [proveedorId, setProveedorId] = useState(
    compraInicial ? String(compraInicial.proveedorId) : '',
  )
  const [numeroFactura, setNumeroFactura] = useState(compraInicial?.numeroFactura ?? '')
  const [notas, setNotas] = useState(compraInicial?.notas ?? '')
  const [lineas, setLineas] = useState(
    compraInicial?.items.length ? compraInicial.items.map(lineaDesde) : [lineaVacia()],
  )
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)
  const [guardada, setGuardada] = useState(null)
  const [formulario, setFormulario] = useState(null)
  const contenedor = useRef(null)

  const completas = lineas.filter((l) => l.varianteId && l.cantidad && l.costoUnitario)
  const totalOrientativo = completas.reduce(
    (suma, l) => suma + Number(l.cantidad) * Number(l.costoUnitario), 0,
  )

  const actualizar = useCallback((indice, cambios) => {
    setLineas((actuales) => actuales.map((linea, i) => (
      i === indice ? { ...linea, ...cambios, error: undefined } : linea
    )))
  }, [])

  const agregarLinea = useCallback(() => {
    setLineas((actuales) => [...actuales, lineaVacia()])
    requestAnimationFrame(() => {
      const buscadores = contenedor.current?.querySelectorAll('input[data-buscador]')
      buscadores?.[buscadores.length - 1]?.focus()
    })
  }, [])

  function enfocarSiguiente(selector, indice) {
    requestAnimationFrame(() => {
      contenedor.current?.querySelectorAll(selector)?.[indice]?.focus()
    })
  }

  function alTeclearEnCosto(evento, indice) {
    if (evento.key !== 'Enter') return
    evento.preventDefault()
    if (indice === lineas.length - 1) agregarLinea()
    else enfocarSiguiente('input[data-buscador]', indice + 1)
  }

  async function guardar() {
    if (!proveedorId || completas.length === 0 || guardando) return
    setGuardando(true)
    setError(null)
    try {
      // Sin total en el cuerpo: no hay campo donde mandarlo.
      const datos = {
        proveedorId: Number(proveedorId),
        numeroFactura: numeroFactura.trim() || null,
        notas: notas.trim() || null,
        lineas: completas.map((l) => ({
          varianteId: Number(l.varianteId),
          cantidad: Number(l.cantidad),
          costoUnitario: Number(l.costoUnitario),
        })),
      }
      const respuesta = compraInicial
        ? await apiCompras.actualizarBorrador(compraInicial.id, datos)
        : await apiCompras.crearBorrador(datos)
      setGuardada(respuesta)
      await compras.recargar()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  if (guardada) {
    return <CompraGuardada compra={guardada} alCerrar={alCerrar}
                           alRecibir={() => alRecibir(guardada)} />
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>{compraInicial ? `Editar el borrador ${compraInicial.consecutivo}` : 'Registrar una compra'}</h1>
        <Boton variante="plano" onClick={alCerrar}>Volver al listado</Boton>
      </div>

      {compraInicial && (
        <Aviso tipo="alerta" titulo="La compra original quedó anulada">
          Este borrador tiene las mismas líneas que tenía. Revísalas, corrígelas si hace
          falta, y recíbelo para que el inventario vuelva a decir la verdad — mientras tanto
          el stock queda descuadrado.
        </Aviso>
      )}

      {error && error.codigo !== CODIGOS.validacionFallida && <AvisoDeError error={error} />}

      <div className="formulario__pareja">
        <SelectorConAlta
          etiqueta="Proveedor"
          opciones={compras.proveedoresActivos}
          valor={proveedorId}
          alCambiar={setProveedorId}
          alCrear={async (nombre) => {
            const creado = await apiProveedores.crear({ nombre })
            await compras.recargar()
            return creado
          }}
          textoCrear="Nuevo proveedor"
        />
        <Campo etiqueta="Número de factura (opcional)" value={numeroFactura}
               onChange={(e) => setNumeroFactura(e.target.value)} />
      </div>

      <div className="tabla-envoltura tabla-envoltura--captura" ref={contenedor}>
        <table className="tabla carga__tabla">
          <thead>
            <tr>
              <th>Variante</th>
              <th className="numero">Cantidad</th>
              <th className="numero">Costo unitario</th>
              <th className="numero">Subtotal</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lineas.map((linea, indice) => (
              <tr key={linea.clave}>
                <td>
                  {/* `todas` y no `filas`: una variante recien creada aqui no tiene
                      ningun movimiento todavia —la mercancia esta llegando— y aun asi
                      hay que poder ponerla en la factura. */}
                  <BuscadorDeVariante
                    filas={catalogo.todas}
                    valor={linea.varianteId}
                    indice={indice}
                    error={linea.error}
                    alElegir={(id) => {
                      actualizar(indice, { varianteId: id })
                      enfocarSiguiente('input[data-cantidad]', indice)
                    }}
                  />
                </td>
                <td className="carga__celda-numero">
                  <Campo etiqueta="">
                    {(props) => (
                      <input
                        {...props}
                        data-cantidad="true"
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
                        data-costo="true"
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
          <Boton icono={Plus} onClick={() => setFormulario({ paso: 'producto' })}>
            El producto no está en el catálogo
          </Boton>
          <p className="carga__pista">
            Tab pasa al campo siguiente. Enter en el costo agrega otra línea.
          </p>
        </div>
        <div className="registro__cierre">
          <p className="registro__total" aria-live="polite">
            <span className="texto-secundario">Total aproximado</span>
            <span className="monto">{formatearPesos(totalOrientativo)}</span>
          </p>
          <Boton variante="principal" onClick={guardar} ocupado={guardando}
                 disabled={!proveedorId || completas.length === 0}
                 textoOcupado="Guardando…">
            Guardar borrador
          </Boton>
        </div>
      </div>

      {formulario?.paso === 'producto' && (
        <FormularioProducto
          catalogo={catalogo}
          alCerrar={() => setFormulario(null)}
          alGuardar={async (producto) => {
            await catalogo.recargar()
            // Un producto sin variantes no se puede comprar: el segundo paso no es
            // opcional, asi que se encadena en vez de ofrecerse.
            setFormulario({ paso: 'variante', producto })
          }}
        />
      )}

      {formulario?.paso === 'variante' && (
        <FormularioVariante
          catalogo={catalogo}
          productoInicial={formulario.producto}
          alCerrar={() => setFormulario(null)}
          alGuardar={async () => { await catalogo.recargar(); setFormulario(null) }}
        />
      )}
    </>
  )
}

/**
 * Lo que se ve al guardar. EL TOTAL QUE SE MUESTRA ES EL DEL SERVIDOR, tomado de
 * la respuesta y no del que la pantalla venia sumando: si los dos discreparan, el
 * que manda es el que quedo guardado.
 */
function CompraGuardada({ compra, alCerrar, alRecibir }) {
  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Compra {compra.consecutivo}</h1>
      </div>

      <Aviso tipo="exito" titulo="El borrador quedó guardado">
        Todavía no ha entrado nada al inventario. Eso pasa al recibirla.
      </Aviso>

      <p className="registro__total registro__total--confirmado">
        <span className="texto-secundario">Total registrado</span>
        <span className="monto" data-total-servidor="true">{formatearPesos(compra.total)}</span>
      </p>

      <div className="modal__acciones">
        <Boton variante="plano" onClick={alCerrar}>Volver al listado</Boton>
        <Boton variante="principal" onClick={alRecibir}>Recibir ahora</Boton>
      </div>
    </>
  )
}

let siguienteClave = 0

function lineaVacia() {
  siguienteClave += 1
  return { clave: siguienteClave, varianteId: '', cantidad: '', costoUnitario: '' }
}

function lineaDesde(item) {
  siguienteClave += 1
  return {
    clave: siguienteClave,
    varianteId: String(item.varianteId),
    cantidad: String(item.cantidad),
    costoUnitario: String(item.costoUnitario),
  }
}
