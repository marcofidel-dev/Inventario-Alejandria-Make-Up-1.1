import { useEffect, useState } from 'react'

import { compras as apiCompras } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { descripcionDe } from '../componentes/BuscadorDeVariante.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'

/**
 * Descartar y anular son DOS COMPONENTES SEPARADOS a proposito, no uno con un
 * parametro.
 *
 * Tienen consecuencias distintas —descartar no revierte nada porque nunca hubo
 * mercancia; anular devuelve stock y puede dejar variantes en negativo— y lo unico
 * que impide que se confundan es que no se parezcan: distinto boton, distinto
 * texto, distinta advertencia. Un componente comun con un `tipo` acabaria, a la
 * primera refactorizacion, siendo el mismo dialogo con otro titulo.
 *
 * Los dos exigen motivo. Sin el, una compra que desaparecio del inventario es una
 * pregunta que nadie va a poder responder dentro de seis meses.
 */

export function DescartarCompra({ compra, alCerrar, alHecho }) {
  const [motivo, setMotivo] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  async function descartar() {
    if (!motivo.trim() || enviando) return
    setEnviando(true)
    setError(null)
    try {
      await apiCompras.descartar(compra.id, motivo.trim())
      await alHecho()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal
      titulo={`Descartar la compra ${compra.consecutivo}`}
      alCerrar={alCerrar}
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={enviando}>Cancelar</Boton>
          <Boton onClick={descartar} ocupado={enviando} disabled={!motivo.trim()}
                 textoOcupado="Descartando…">
            Descartar el borrador
          </Boton>
        </>
      }
    >
      <div className="formulario">
        <Aviso tipo="info">
          Este borrador nunca entró al inventario, así que no hay nada que devolver. Queda
          registrado como descartado, con su motivo, y su número no se reutiliza.
        </Aviso>

        <Campo
          etiqueta="¿Por qué se descarta?"
          value={motivo}
          autoFocus
          ayuda="Por ejemplo: el proveedor canceló el despacho."
          onChange={(e) => setMotivo(e.target.value)}
        />

        {error && <AvisoDeError error={error} />}
      </div>
    </Modal>
  )
}

export function AnularCompra({ compra, catalogo, alCerrar, alHecho }) {
  const [previa, setPrevia] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    let vigente = true
    apiCompras.previaDeAnulacion(compra.id)
      .then((datos) => { if (vigente) setPrevia(datos) })
      .catch((fallo) => { if (vigente) setError(fallo) })
    return () => { vigente = false }
  }, [compra.id])

  async function anular() {
    if (!motivo.trim() || enviando) return
    setEnviando(true)
    setError(null)
    try {
      await apiCompras.anular(compra.id, motivo.trim())
      await alHecho()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setEnviando(false)
    }
  }

  const negativas = previa?.lineas.filter((linea) => linea.quedaNegativo) ?? []

  return (
    <Modal
      titulo={`Anular la compra ${compra.consecutivo}`}
      alCerrar={alCerrar}
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={enviando}>Cancelar</Boton>
          <Boton variante="peligro" onClick={anular} ocupado={enviando}
                 disabled={!motivo.trim()} textoOcupado="Anulando…">
            Anular y devolver el stock
          </Boton>
        </>
      }
    >
      <div className="formulario">
        <Aviso tipo="alerta" titulo="Esto devuelve el stock">
          Se van a restar del inventario las unidades que entraron con esta compra, y el
          costo promedio se recalcula como si la compra nunca hubiera existido.
        </Aviso>

        {/* Lo que de verdad hay que ver antes de apretar: cuales quedan en negativo
            y en cuanto. Un negativo no se bloquea —dice la verdad, que se vendio
            mercancia que no habia— pero enterarse despues no sirve de nada. */}
        {negativas.length > 0 && (
          <Aviso tipo="error" titulo="Estas variantes quedan con stock negativo">
            Ya se vendieron unidades de esta compra, así que al anularla el sistema queda
            debiendo existencias. Es información real y conviene revisarla.
            <ul className="aviso__detalles">
              {negativas.map((linea) => (
                <li key={linea.varianteId}>
                  {describir(catalogo, linea.varianteId)}: {linea.stockActual} →{' '}
                  {linea.stockResultante}
                </li>
              ))}
            </ul>
          </Aviso>
        )}

        <Campo
          etiqueta="¿Por qué se anula?"
          value={motivo}
          autoFocus
          ayuda="Por ejemplo: llegó mercancía equivocada y se devolvió."
          onChange={(e) => setMotivo(e.target.value)}
        />

        {error && <AvisoDeError error={error} />}
      </div>
    </Modal>
  )
}

function describir(catalogo, varianteId) {
  const fila = catalogo.filas.find((f) => String(f.id) === String(varianteId))
  return fila ? descripcionDe(fila) : `Variante ${varianteId}`
}
