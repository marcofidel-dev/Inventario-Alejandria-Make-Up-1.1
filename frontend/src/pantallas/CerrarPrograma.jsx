import { useState } from 'react'
import { Power } from 'lucide-react'

import { sistema } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Modal } from '../componentes/Modal.jsx'

/**
 * El botón "Cerrar programa" del encabezado, para los dos roles.
 *
 * El backend respalda antes de apagar y, si el respaldo falla, no apaga: avisa
 * el motivo y pregunta si cerrar de todas formas. Esta pantalla es el reflejo
 * de esos dos pasos —nunca uno junto con el otro, porque confirmar sin saber
 * si el respaldo salió bien sería adelantar algo que todavía no se sabe.
 *
 * `alApagado` lo cumple quien monta este botón reemplazando TODA la pantalla
 * por el aviso final: el mensaje "se cerró" se pinta antes de que el servidor
 * de verdad se apague, que ocurre unos milisegundos después en el backend.
 */
export function BotonCerrarPrograma({ alApagado }) {
  const [paso, setPaso] = useState(null) // null | 'confirmar' | 'fallo'
  const [enviando, setEnviando] = useState(false)
  const [motivo, setMotivo] = useState('')
  const [error, setError] = useState(null)

  function cerrarModal() {
    setPaso(null)
    setError(null)
  }

  async function pedirApagado(forzar) {
    if (enviando) return
    setEnviando(true)
    setError(null)
    try {
      const respuesta = await sistema.apagar(forzar)
      if (respuesta.exitoso) {
        alApagado()
      } else {
        setMotivo(respuesta.motivo)
        setPaso('fallo')
      }
    } catch (fallo) {
      setError(fallo)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <Boton variante="plano" icono={Power} onClick={() => setPaso('confirmar')}>
        Cerrar programa
      </Boton>

      {paso === 'confirmar' && (
        <Modal
          titulo="¿Cerrar el programa?"
          alCerrar={cerrarModal}
          acciones={
            <>
              <Boton variante="plano" onClick={cerrarModal} disabled={enviando}>Cancelar</Boton>
              <Boton onClick={() => pedirApagado(false)} ocupado={enviando} textoOcupado="Cerrando…">
                Sí, cerrar el programa
              </Boton>
            </>
          }
        >
          <Aviso tipo="info">Se hace un respaldo antes de apagar.</Aviso>
          {error && <AvisoDeError error={error} />}
        </Modal>
      )}

      {paso === 'fallo' && (
        <Modal
          titulo="El respaldo falló"
          alCerrar={cerrarModal}
          acciones={
            <>
              <Boton variante="plano" onClick={cerrarModal} disabled={enviando}>Cancelar</Boton>
              <Boton onClick={() => pedirApagado(true)} ocupado={enviando} textoOcupado="Cerrando…">
                Cerrar de todas formas
              </Boton>
            </>
          }
        >
          <Aviso tipo="alerta" titulo={motivo}>¿Cerrar de todas formas?</Aviso>
          {error && <AvisoDeError error={error} />}
        </Modal>
      )}
    </>
  )
}
