import { useEffect, useState } from 'react'
import { Ban } from 'lucide-react'

import { ventas as apiVentas } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'
import { PERMISOS, useSesion } from '../sesion/SesionContext.jsx'
import { formatearPesos } from './Catalogo.jsx'

/**
 * Las ventas de un dia.
 *
 * Sirve para encontrar la venta que se va a anular y, mas adelante, la que hay que
 * reimprimir. SIN COSTOS NI MARGENES, como el resto del modulo: el resumen que manda
 * el servidor no tiene donde ponerlos.
 *
 * La fecha se elige con el selector nativo del sistema. Un calendario propio serian
 * doscientas lineas para hacer peor lo que el sistema operativo ya hace en el idioma
 * de quien lo usa.
 */
export function Ventas() {
  const { puede } = useSesion()
  const [fecha, setFecha] = useState(hoy)
  const [filas, setFilas] = useState([])
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)
  const [anulando, setAnulando] = useState(null)

  useEffect(() => {
    let vigente = true
    setCargando(true)
    setError(null)
    apiVentas.listar(fecha)
      .then((respuesta) => { if (vigente) setFilas(respuesta) })
      .catch((fallo) => { if (vigente) setError(fallo) })
      .finally(() => { if (vigente) setCargando(false) })
    return () => { vigente = false }
  }, [fecha])

  async function recargar() {
    setFilas(await apiVentas.listar(fecha))
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Ventas</h1>
        <Campo
          etiqueta="Día"
          type="date"
          value={fecha}
          onChange={(e) => setFecha(e.target.value || hoy())}
        />
      </div>

      {error && <AvisoDeError error={error} alReintentar={() => setFecha(fecha)} />}

      {cargando
        ? <p className="texto-secundario">Buscando…</p>
        : <Tabla filas={filas} puedeAnular={puede(PERMISOS.anularVentas)}
                 alAnular={setAnulando} />}

      {anulando && (
        <AnularVenta
          venta={anulando}
          alCerrar={() => setAnulando(null)}
          alAnular={async () => { setAnulando(null); await recargar() }}
        />
      )}
    </>
  )
}

function Tabla({ filas, puedeAnular, alAnular }) {
  if (filas.length === 0) {
    return (
      <div className="estado-vacio">
        <p>No hubo ventas ese día.</p>
      </div>
    )
  }

  return (
    <div className="tabla-envoltura">
      <table className="tabla">
        <thead>
          <tr>
            <th>N.º</th>
            <th>Hora</th>
            <th className="numero">Total</th>
            <th>Cómo pagó</th>
            <th>Estado</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {filas.map((venta) => (
            <tr key={venta.id} className={venta.estado === 'ANULADA' ? 'inactiva' : undefined}>
              <td>{venta.consecutivo}</td>
              <td>{soloHora(venta.fecha)}</td>
              <td className="numero monto">{formatearPesos(venta.total)}</td>
              <td>{COMO_SE_DICE[venta.metodoPago] ?? venta.metodoPago}</td>
              <td>
                {venta.estado === 'ANULADA'
                  ? (
                    <>
                      <span className="insignia insignia--neutra">anulada</span>
                      {venta.motivoAnulacion && (
                        <div className="texto-tenue">{venta.motivoAnulacion}</div>
                      )}
                    </>
                  )
                  : <span className="texto-secundario">completada</span>}
              </td>
              <td className="fila__acciones">
                {/* Anular es destructivo pero no es lo que se viene a hacer aqui. En
                    rojo y en cada fila, veinte botones de anular gritan mas que los
                    datos y ademas se pulsan por accidente. El peso va en la
                    confirmacion, que es donde de verdad se decide. */}
                {puedeAnular && venta.estado === 'COMPLETADA' && (
                  <Boton variante="plano" icono={Ban} onClick={() => alAnular(venta)}>
                    Anular
                  </Boton>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * La confirmacion. Dice las dos consecuencias ANTES de pedir el motivo, porque son lo
 * que hace falta saber para decidir, y la segunda sorprende: la plata sale de la caja
 * de HOY, no de la del dia en que se cobro. Sobre una venta de la semana pasada, eso
 * es un movimiento en el arqueo de esta noche.
 */
function AnularVenta({ venta, alCerrar, alAnular }) {
  const [motivo, setMotivo] = useState('')
  const [anulando, setAnulando] = useState(false)
  const [error, setError] = useState(null)

  async function anular() {
    if (!motivo.trim() || anulando) return
    setAnulando(true)
    setError(null)
    try {
      await apiVentas.anular(venta.id, motivo.trim())
      await alAnular()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setAnulando(false)
    }
  }

  return (
    <Modal titulo={`Anular la venta ${venta.consecutivo}`} alCerrar={alCerrar}>
      <Aviso tipo="alerta" titulo="Esto deshace la venta">
        Los productos vuelven al inventario.
        {venta.metodoPago === 'EFECTIVO' && (
          <> Y como se pagó en efectivo, salen {formatearPesos(venta.total)} de la caja
            de hoy — no de la del día en que se cobró.</>
        )}
      </Aviso>

      {error && <AvisoDeError error={error} />}

      <div className="formulario">
        <Campo
          etiqueta="¿Por qué se anula?"
          value={motivo}
          onChange={(e) => setMotivo(e.target.value)}
          ayuda="Obligatorio. Dentro de seis meses, es lo único que va a explicar por qué
                 esta venta desapareció del inventario y del cajón."
        />
      </div>

      <div className="modal__acciones">
        <Boton variante="plano" onClick={alCerrar} disabled={anulando}>Cancelar</Boton>
        <Boton variante="peligro" onClick={anular} ocupado={anulando} textoOcupado="Anulando…"
               disabled={!motivo.trim()}>
          Anular la venta
        </Boton>
      </div>
    </Modal>
  )
}

/** Los metodos, dichos como se dicen en el mostrador. */
const COMO_SE_DICE = {
  EFECTIVO: 'Efectivo',
  TARJETA: 'Tarjeta',
  NEQUI: 'Nequi',
  DAVIPLATA: 'Daviplata',
  TRANSFERENCIA: 'Transferencia',
}

/** Hoy en local, en el formato que espera <input type="date">. */
function hoy() {
  const ahora = new Date()
  const mes = String(ahora.getMonth() + 1).padStart(2, '0')
  const dia = String(ahora.getDate()).padStart(2, '0')
  return `${ahora.getFullYear()}-${mes}-${dia}`
}

// La fecha llega como TEXT ISO de ancho fijo: se corta por posicion, no se parsea.
const soloHora = (fecha) => (fecha ?? '').slice(11, 16)
