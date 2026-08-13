import { useState } from 'react'
import { Plus } from 'lucide-react'

import { Boton } from './Boton.jsx'
import { Campo } from './Campo.jsx'

/**
 * Un desplegable de lo que ya existe, con la opcion de crear algo nuevo en el
 * mismo sitio.
 *
 * NO ES TEXTO LIBRE, Y ESA ES LA RAZON DE QUE EXISTA. El indice de V4 atrapa las
 * variaciones de tilde y mayuscula: "Loreal" y "LORÉAL" chocan. Lo que ningun
 * indice puede atrapar es "Loreal" contra "L'Oréal Paris", que normalizados
 * siguen siendo cadenas distintas. Un campo de texto libre garantiza que en seis
 * meses el catalogo tenga tres marcas que son la misma. Elegir de una lista lo
 * hace imposible por construccion.
 */
export function SelectorConAlta({
  etiqueta,
  opciones,
  valor,
  alCambiar,
  alCrear,
  textoCrear = 'Crear nueva',
  error,
  deshabilitado = false,
}) {
  const [creando, setCreando] = useState(false)
  const [nombreNuevo, setNombreNuevo] = useState('')
  const [guardando, setGuardando] = useState(false)
  const [errorAlCrear, setErrorAlCrear] = useState(null)

  async function crear() {
    if (!nombreNuevo.trim() || guardando) return
    setGuardando(true)
    setErrorAlCrear(null)
    try {
      const creada = await alCrear(nombreNuevo.trim())
      alCambiar(String(creada.id))
      setCreando(false)
      setNombreNuevo('')
    } catch (fallo) {
      // El mensaje del backend ya explica con que choca: "Ya existe la marca
      // «Loréal»…". Reescribirlo aqui seria perder ese dato.
      setErrorAlCrear(fallo.message)
    } finally {
      setGuardando(false)
    }
  }

  if (creando) {
    return (
      <div className="campo">
        <Campo
          etiqueta={`${etiqueta} — nueva`}
          value={nombreNuevo}
          error={errorAlCrear}
          autoFocus
          onChange={(e) => setNombreNuevo(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); crear() }
            if (e.key === 'Escape') { e.preventDefault(); setCreando(false); setErrorAlCrear(null) }
          }}
        />
        <div className="modal__acciones">
          <Boton variante="plano" onClick={() => { setCreando(false); setErrorAlCrear(null) }}>
            Cancelar
          </Boton>
          <Boton variante="principal" onClick={crear} ocupado={guardando}
                 disabled={!nombreNuevo.trim()}>
            Crear
          </Boton>
        </div>
      </div>
    )
  }

  return (
    <div className="selector-con-alta">
      <Campo etiqueta={etiqueta} error={error}>
        {(props) => (
          <select
            {...props}
            value={valor ?? ''}
            disabled={deshabilitado}
            onChange={(e) => alCambiar(e.target.value)}
          >
            <option value="">Elegir…</option>
            {opciones.map((opcion) => (
              <option key={opcion.id} value={opcion.id}>{opcion.nombre}</option>
            ))}
          </select>
        )}
      </Campo>
      <Boton variante="plano" icono={Plus} onClick={() => setCreando(true)}
             disabled={deshabilitado}>
        {textoCrear}
      </Boton>
    </div>
  )
}
