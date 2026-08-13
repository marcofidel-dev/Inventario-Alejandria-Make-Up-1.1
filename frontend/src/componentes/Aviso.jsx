import { AlertTriangle, CheckCircle2, Info, WifiOff, XCircle } from 'lucide-react'

import { Boton } from './Boton.jsx'

const ICONOS = {
  error: XCircle,
  exito: CheckCircle2,
  alerta: AlertTriangle,
  info: Info,
  sinConexion: WifiOff,
}

/**
 * Un aviso con su color semantico.
 *
 * Los colores salen de los semanticos, nunca de los rosas de la marca: entre si
 * los rosas tienen 1.11 de separacion y a un metro de la pantalla son el mismo
 * color, asi que no pueden distinguir un error de un exito.
 */
export function Aviso({ tipo = 'info', titulo, children, detalles = [], accion }) {
  const esSinConexion = tipo === 'sinConexion'
  const Icono = ICONOS[tipo] ?? Info
  const clase = esSinConexion ? 'aviso aviso--error' : `aviso aviso--${tipo}`

  return (
    <div className={clase} role={tipo === 'error' || esSinConexion ? 'alert' : 'status'}>
      <Icono size={18} className="aviso__icono" aria-hidden="true" />
      <div className="aviso__cuerpo">
        {titulo && <strong>{titulo}</strong>}
        {children && <span>{children}</span>}
        {detalles.length > 0 && (
          <ul className="aviso__detalles">
            {detalles.map((detalle) => <li key={detalle}>{detalle}</li>)}
          </ul>
        )}
        {accion && (
          <div>
            <Boton variante="plano" onClick={accion.alPulsar}>{accion.texto}</Boton>
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * Traduce un ErrorApi a un aviso, distinguiendo los tres casos que en pantalla
 * no significan lo mismo: no hay red, el servidor fallo, o el servidor dijo no.
 */
export function AvisoDeError({ error, alReintentar }) {
  if (!error) return null

  if (error.esFalloDeRed) {
    return (
      <Aviso
        tipo="sinConexion"
        titulo="No hay conexión con el servidor"
        accion={alReintentar ? { texto: 'Reintentar', alPulsar: alReintentar } : undefined}
      >
        El programa del servidor puede estar apagado. Si acabas de encender el equipo,
        espera unos segundos y reintenta.
      </Aviso>
    )
  }

  if (error.esErrorDelServidor) {
    return (
      <Aviso
        tipo="error"
        titulo="El servidor falló"
        accion={alReintentar ? { texto: 'Reintentar', alPulsar: alReintentar } : undefined}
      >
        {error.message}
      </Aviso>
    )
  }

  return <Aviso tipo="error" detalles={error.detalles}>{error.message}</Aviso>
}
