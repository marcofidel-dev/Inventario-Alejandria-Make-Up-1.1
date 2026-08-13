import { useState } from 'react'
import { ShieldCheck } from 'lucide-react'

import { autenticacion } from '../api/endpoints.js'
import { AvisoDeError, Aviso } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'

const LARGO_PIN = 4

/**
 * El primer arranque: crear la administradora antes de cualquier otra cosa.
 *
 * EL PIN SE PIDE DOS VECES, y no es ceremonia. No existe ningun mecanismo para
 * restablecer un PIN: ni endpoint, ni pantalla, ni pregunta de seguridad. Un dedo
 * torcido aqui, en la unica cuenta que administra el sistema, deja la tienda
 * afuera para siempre y sin arreglo desde la aplicacion. Es el mismo agujero que
 * el backend cierra negandose a desactivar a la ultima DUENA, entrando por otra
 * puerta.
 */
export function ConfiguracionInicial({ alConfigurar }) {
  const [nombre, setNombre] = useState('')
  const [pin, setPin] = useState('')
  const [confirmacion, setConfirmacion] = useState('')
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const pinCompleto = pin.length === LARGO_PIN
  const coinciden = pinCompleto && pin === confirmacion
  const puedeGuardar = nombre.trim().length > 0 && coinciden && !guardando
  const errorDeConfirmacion = confirmacion.length === LARGO_PIN && !coinciden
    ? 'Los dos PIN no coinciden.'
    : undefined

  async function guardar() {
    if (!puedeGuardar) return
    setGuardando(true)
    setError(null)
    try {
      alConfigurar(await autenticacion.configuracionInicial(nombre.trim(), pin))
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  return (
    <main className="login">
      <div className="login__caja">
        <ShieldCheck size={32} aria-hidden="true" />
        <h1 className="login__titulo">Configuración inicial</h1>
        <p className="texto-secundario">
          No hay ningún usuario todavía. Crea la cuenta de administradora para empezar.
        </p>

        <div className="formulario">
          <Campo
            etiqueta="Nombre"
            value={nombre}
            autoFocus
            onChange={(e) => setNombre(e.target.value)}
          />
          <Campo
            etiqueta="PIN de 4 dígitos"
            type="password"
            inputMode="numeric"
            value={pin}
            ayuda="Se pide dos veces porque no hay forma de restablecerlo."
            onChange={(e) => setPin(soloDigitos(e.target.value))}
          />
          <Campo
            etiqueta="Repetir el PIN"
            type="password"
            inputMode="numeric"
            value={confirmacion}
            error={errorDeConfirmacion}
            onChange={(e) => setConfirmacion(soloDigitos(e.target.value))}
            onKeyDown={(e) => { if (e.key === 'Enter') guardar() }}
          />
        </div>

        {error && <AvisoDeError error={error} />}

        {!error && (
          <Aviso tipo="alerta">
            Anota este PIN en un lugar seguro. El sistema no tiene forma de recuperarlo.
          </Aviso>
        )}

        <Boton variante="principal" onClick={guardar} ocupado={guardando}
               disabled={!puedeGuardar} textoOcupado="Creando…">
          Crear administradora
        </Boton>
      </div>
    </main>
  )
}

function soloDigitos(texto) {
  return texto.replace(/\D/g, '').slice(0, LARGO_PIN)
}
