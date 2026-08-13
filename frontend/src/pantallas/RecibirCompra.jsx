import { useCallback, useEffect, useState } from 'react'

import { caja as apiCaja, compras as apiCompras } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { descripcionDe } from '../componentes/BuscadorDeVariante.jsx'
import { formatearPesos } from './Catalogo.jsx'

/** Como se pago. Sin opcion marcada: es una pregunta, no un valor por defecto. */
const FORMAS_DE_PAGO = [
  { id: 'efectivo', texto: 'Efectivo de la caja' },
  { id: 'transferencia', texto: 'Transferencia o consignación' },
  { id: 'credito', texto: 'Crédito del proveedor' },
]

/**
 * Recibir una compra: el momento en que el inventario cambia.
 *
 * ANTES DE CONFIRMAR SE VE QUE VA A PASAR: cuantas unidades entran a cada
 * variante, el costo promedio actual y el que quedaria, y una advertencia
 * destacada si alguna queda con el costo por encima del precio de venta o con el
 * margen por debajo del minimo.
 *
 * ESA VISTA PREVIA ES INFORMATIVA Y NADA MAS. Al confirmar se manda solo el id de
 * la compra: ningun numero de esta pantalla vuelve al servidor. El servidor
 * recalcula con el stock de ese instante, porque entre que se abrio esta pantalla
 * y se apreto el boton alguien pudo haber vendido, y entonces la previa quedo vieja
 * y quien tiene razon es el ledger.
 *
 * EL PAGO NO LO ESCRIBE ESTE MODULO. Compras no toca la caja: si se pago en
 * efectivo del cajon, la pantalla hace una segunda llamada, independiente, al
 * endpoint de movimientos de caja que ya existe. Preguntarlo no es burocracia —
 * pagar $800.000 en efectivo y no registrarlo deja el cierre de ese dia con un
 * faltante de $800.000 y a alguien buscando plata que no falta.
 */
export function RecibirCompra({ compra, catalogo, compras, alCerrar }) {
  const [previa, setPrevia] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)
  const [pago, setPago] = useState('')
  const [confirmando, setConfirmando] = useState(false)
  const [resultado, setResultado] = useState(null)
  const [sesionDeCaja, setSesionDeCaja] = useState(null)

  const cargar = useCallback(async () => {
    setCargando(true)
    setError(null)
    try {
      setPrevia(await apiCompras.previaDeRecepcion(compra.id))
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCargando(false)
    }
  }, [compra.id])

  useEffect(() => { cargar() }, [cargar])

  // Un 404 aqui no es un fallo: significa que no hay caja abierta, que es un
  // estado normal —el pedido puede llegar antes de abrir la tienda.
  useEffect(() => {
    let vigente = true
    apiCaja.sesionActual()
      .then((sesion) => { if (vigente) setSesionDeCaja(sesion) })
      .catch(() => { if (vigente) setSesionDeCaja(null) })
    return () => { vigente = false }
  }, [])

  async function confirmar() {
    if (!pago || confirmando) return
    setConfirmando(true)
    setError(null)
    try {
      // Solo el id. Ningun numero de la previa viaja de vuelta.
      const recibida = await apiCompras.recibir(compra.id)
      await Promise.all([catalogo.recargar(), compras.recargar()])
      setResultado({ recibida, gasto: await registrarGasto(recibida) })
    } catch (fallo) {
      setError(fallo)
    } finally {
      setConfirmando(false)
    }
  }

  /**
   * La segunda llamada, independiente. Si falla, la recepcion sigue siendo valida
   * —la mercancia entro— y hay que decirlo tal cual en vez de mostrar un error que
   * haga pensar que no se recibio nada.
   */
  async function registrarGasto(recibida) {
    if (pago !== 'efectivo' || !sesionDeCaja) return null
    try {
      await apiCaja.registrarMovimiento({
        tipo: 'GASTO',
        monto: recibida.total,
        concepto: `Compra ${recibida.consecutivo} · ${nombreDelProveedor(compras, compra)}`,
      })
      return { ok: true }
    } catch (fallo) {
      return { ok: false, fallo }
    }
  }

  async function reintentarGasto() {
    setConfirmando(true)
    const gasto = await registrarGasto(resultado.recibida)
    setResultado((actual) => ({ ...actual, gasto }))
    setConfirmando(false)
  }

  if (resultado) {
    return (
      <Recibida
        resultado={resultado}
        pago={pago}
        haySesionDeCaja={Boolean(sesionDeCaja)}
        reintentando={confirmando}
        alReintentarGasto={reintentarGasto}
        alCerrar={alCerrar}
      />
    )
  }

  if (cargando) return <p className="texto-secundario">Calculando lo que va a pasar…</p>

  if (error && !previa) {
    return (
      <>
        <AvisoDeError error={error} alReintentar={cargar} />
        <div className="modal__acciones">
          <Boton variante="plano" onClick={alCerrar}>Volver al listado</Boton>
        </div>
      </>
    )
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Recibir la compra {previa.consecutivo}</h1>
        <Boton variante="plano" onClick={alCerrar}>Volver al listado</Boton>
      </div>

      {previa.hayAdvertencias && (
        <Aviso tipo="alerta" titulo="Revisa los costos antes de confirmar">
          Alguna variante queda con el costo por encima de su precio de venta, o con un
          margen muy bajo. Se puede recibir igual, pero conviene mirar el precio.
        </Aviso>
      )}

      {error && <AvisoDeError error={error} />}

      <div className="tabla-envoltura">
        <table className="tabla">
          <thead>
            <tr>
              <th>Variante</th>
              <th className="numero">Entran</th>
              <th className="numero">Stock</th>
              <th className="numero">Costo promedio</th>
              <th className="numero">Margen</th>
            </tr>
          </thead>
          <tbody>
            {previa.lineas.map((linea, indice) => (
              <tr key={`${linea.varianteId}-${indice}`}
                  className={linea.costoSuperaPrecio || linea.margenBajo ? 'fila--alerta' : undefined}>
                <td>{describir(catalogo, linea.varianteId)}</td>
                <td className="numero monto">{linea.cantidad}</td>
                <td className="numero">
                  <span className="monto">{linea.stockActual}</span>
                  <span className="texto-secundario"> → </span>
                  <span className="monto">{linea.stockResultante}</span>
                </td>
                <td className="numero">
                  <span className="monto">{formatearPesos(linea.costoPromedioActual)}</span>
                  <span className="texto-secundario"> → </span>
                  <span className="monto">{formatearPesos(linea.costoPromedioResultante)}</span>
                </td>
                <td className="numero">
                  <span className="monto">{linea.margenPorcentaje}%</span>
                  {linea.costoSuperaPrecio && (
                    <> <span className="insignia insignia--error">sobre el precio</span></>
                  )}
                  {linea.margenBajo && !linea.costoSuperaPrecio && (
                    <> <span className="insignia insignia--alerta">margen bajo</span></>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <fieldset className="formulario formulario--angosto">
        <legend>¿Cómo se pagó esta compra?</legend>
        {FORMAS_DE_PAGO.map((forma) => (
          <label key={forma.id} className="opcion">
            <input
              type="radio"
              name="forma-de-pago"
              value={forma.id}
              checked={pago === forma.id}
              onChange={() => setPago(forma.id)}
            />
            {forma.texto}
          </label>
        ))}

        {pago === 'efectivo' && !sesionDeCaja && (
          <Aviso tipo="info">
            No hay una caja abierta ahora mismo, así que el gasto no se puede registrar
            todavía. Queda para registrarlo desde la pantalla de caja cuando se abra.
          </Aviso>
        )}
        {pago === 'efectivo' && sesionDeCaja && (
          <Aviso tipo="info">
            Se va a registrar también un gasto de {formatearPesos(previa.total)} en la caja
            abierta.
          </Aviso>
        )}
      </fieldset>

      <Aviso tipo="alerta" titulo="Esto cambia el inventario">
        Al confirmar entran las unidades al stock y se recalcula el costo promedio.
        Después la compra ya no se puede editar: para deshacerla habría que anularla.
      </Aviso>

      <div className="modal__acciones">
        <Boton variante="plano" onClick={alCerrar} disabled={confirmando}>Cancelar</Boton>
        <Boton variante="principal" onClick={confirmar} ocupado={confirmando}
               disabled={!pago} textoOcupado="Recibiendo…">
          {pago ? 'Confirmar la recepción' : 'Primero indica cómo se pagó'}
        </Boton>
      </div>
    </>
  )
}

/**
 * Despues de recibir. Los dos actos se informan por separado porque pueden terminar
 * distinto: la mercancia puede haber entrado y el gasto no haberse registrado, y
 * callarlo seria dejar el descuadre para el cierre.
 */
function Recibida({ resultado, pago, haySesionDeCaja, reintentando, alReintentarGasto, alCerrar }) {
  const { recibida, gasto } = resultado

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Compra {recibida.consecutivo}</h1>
      </div>

      <Aviso tipo="exito" titulo="La mercancía entró al inventario">
        Se registraron {recibida.items.length} línea(s) por {formatearPesos(recibida.total)} y se
        recalculó el costo promedio de las variantes.
      </Aviso>

      {gasto?.ok && (
        <Aviso tipo="exito" titulo="El gasto quedó registrado en la caja">
          Sale como {formatearPesos(recibida.total)} a nombre de esta compra.
        </Aviso>
      )}

      {gasto && !gasto.ok && (
        <Aviso tipo="error" titulo="La mercancía entró, pero el gasto no se registró">
          El inventario ya está actualizado. Lo que falló fue el movimiento de caja:{' '}
          {gasto.fallo.message} Si no se registra, el cierre de hoy va a mostrar un
          faltante de {formatearPesos(recibida.total)}.
          <div>
            <Boton variante="principal" onClick={alReintentarGasto} ocupado={reintentando}
                   textoOcupado="Registrando…">
              Reintentar el gasto
            </Boton>
          </div>
        </Aviso>
      )}

      {pago === 'efectivo' && !haySesionDeCaja && (
        <Aviso tipo="alerta" titulo="Falta registrar el gasto en la caja">
          Se pagó en efectivo pero no había una caja abierta. Hay que registrarlo como gasto
          de {formatearPesos(recibida.total)} al abrir la caja, o el cierre no va a cuadrar.
        </Aviso>
      )}

      <div className="modal__acciones">
        <Boton variante="principal" onClick={alCerrar}>Volver al listado</Boton>
      </div>
    </>
  )
}

function describir(catalogo, varianteId) {
  const fila = catalogo.filas.find((f) => String(f.id) === String(varianteId))
  return fila ? descripcionDe(fila) : `Variante ${varianteId}`
}

function nombreDelProveedor(compras, compra) {
  return compras.proveedores.find((p) => p.id === compra.proveedorId)?.nombre ?? 'proveedor'
}
