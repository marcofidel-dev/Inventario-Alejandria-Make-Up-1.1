import { useEffect, useRef, useState } from 'react'

import { caja as apiCaja } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import {
  ContadorDeDenominaciones, conteoParaEnviar, totalContado,
} from '../componentes/ContadorDeDenominaciones.jsx'
import { formatearPesos } from './Catalogo.jsx'

/**
 * Una diferencia de arqueo, dicha en palabras.
 *
 * Distingue sobrante de faltante porque eso si es informacion util —cambia que hay
 * que buscar— y no la presenta como un error porque no lo es. El signo sale del
 * backend, que la calcula como contado menos esperado: positivo es sobrante.
 *
 * Vive aqui y no en Caja.jsx para que la dependencia vaya en un solo sentido: la
 * pantalla de caja ya importa esta, y al reves seria un ciclo.
 */
export function describirDiferencia(diferencia) {
  if (diferencia === 0) return { texto: 'cuadró', tono: 'neutro' }
  if (diferencia > 0) return { texto: 'sobraron', tono: 'favor', cuanto: diferencia }
  return { texto: 'faltaron', tono: 'contra', cuanto: -diferencia }
}

/**
 * Una sesion cerro con diferencia y nadie escribio por que.
 *
 * No obliga a nadie a escribir: solo lo hace visible. Un campo obligatorio que casi
 * siempre sobra se llena de relleno, y para cuando importe ya nadie lo lee en serio.
 */
export function sinExplicar(sesion) {
  return sesion.diferencia !== 0 && (sesion.notas?.length ?? 0) === 0
}

/**
 * Cerrar la caja. Tres pasos, y EL ORDEN ES TODO EL PUNTO.
 *
 *   1. contar     el conteo fisico, con su total a la vista. Nada del sistema.
 *   2. confirmar  "vas a registrar $X". Todavia nada del sistema.
 *   3. revelado   la respuesta del servidor: esperado, contado, diferencia.
 *
 * NO HAY FORMA DE VER EL ESPERADO ANTES, y no porque esta pantalla se contenga: el
 * backend no lo publica en ningun endpoint, y el conteo y el cierre son la misma
 * llamada. Quien cuenta sabiendo el resultado esperado cuenta hasta que le cuadre, y
 * entonces el arqueo deja de medir nada.
 *
 * EL TOTAL DE LO CONTADO SI SE VE MIENTRAS CUENTA. Ese numero es de ella, no del
 * sistema: sin el no puede verificar que digito bien los once campos, y no revela
 * absolutamente nada de lo que el cierre a ciegas protege.
 */
export function CerrarCaja({ sesion, alTerminar, alCancelar }) {
  const [paso, setPaso] = useState('contar')
  const [conteo, setConteo] = useState({})
  const [montoRetirado, setMontoRetirado] = useState('')
  const [baseSiguiente, setBaseSiguiente] = useState('')
  const [arqueo, setArqueo] = useState(null)
  const [cerrando, setCerrando] = useState(false)
  const [error, setError] = useState(null)

  const botonContinuar = useRef(null)

  const contado = totalContado(conteo)

  /**
   * Lo que impide un cierre doble es `ocupado` en el boton, y nada mas.
   *
   * Aqui NO hace falta el ref que si usa el login: alli el envio vivia dentro de un
   * `setPin(fn)`, y StrictMode invoca dos veces las funciones actualizadoras. Esto es
   * un manejador de clic corriente, y React vacia el estado entre dos eventos
   * discretos, asi que para el segundo clic el boton ya esta deshabilitado. Un ref
   * aqui seria una guarda que no se puede demostrar rota, o sea codigo muerto.
   */
  async function cerrar() {
    setCerrando(true)
    setError(null)
    try {
      setArqueo(await apiCaja.cerrar(sesion.id, {
        conteo: conteoParaEnviar(conteo),
        montoRetirado: Number(montoRetirado) || 0,
        baseSiguiente: Number(baseSiguiente) || 0,
      }))
      setPaso('revelado')
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCerrando(false)
    }
  }

  if (paso === 'revelado') {
    return <Revelacion arqueo={arqueo} alTerminar={alTerminar} />
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Cerrar la caja {sesion.consecutivo}</h1>
        <span className="texto-secundario">paso {paso === 'contar' ? 1 : 2} de 2</span>
      </div>

      {error && <AvisoDeError error={error} />}

      {paso === 'contar' ? (
        <>
          <p className="texto-secundario">Cuenta el efectivo que hay en el cajón.</p>

          <ContadorDeDenominaciones
            conteo={conteo}
            alCambiar={(denominacion, valor) =>
              setConteo((actual) => ({ ...actual, [denominacion]: valor }))}
            refSiguiente={botonContinuar}
          />

          <div className="conteo__total">
            <span>Contado</span>
            <strong className="monto conteo__cifra">{formatearPesos(contado)}</strong>
            <span className="texto-tenue">Es tu conteo, no el del sistema.</span>
          </div>

          <div className="modal__acciones">
            <Boton variante="plano" onClick={alCancelar}>Cancelar</Boton>
            <Boton variante="principal" ref={botonContinuar} onClick={() => setPaso('confirmar')}>
              Continuar
            </Boton>
          </div>
        </>
      ) : (
        <>
          <div className="caja__confirmacion">
            <p>
              Vas a registrar <strong className="monto">{formatearPesos(contado)}</strong> en
              efectivo contado.
            </p>
            <p className="texto-secundario">
              Esto es definitivo: la sesión queda cerrada e inmutable, y un dígito de más
              queda permanente.
            </p>
          </div>

          <div className="formulario formulario--angosto">
            <Campo
              etiqueta="Monto que se retira"
              inputMode="numeric"
              value={montoRetirado}
              onChange={(e) => setMontoRetirado(e.target.value.replace(/\D/g, ''))}
              ayuda="Lo que sale del cajón para consignar o guardar."
            />
            <Campo
              etiqueta="Base para mañana"
              inputMode="numeric"
              value={baseSiguiente}
              onChange={(e) => setBaseSiguiente(e.target.value.replace(/\D/g, ''))}
              ayuda="Lo que queda en el cajón. Mañana se propone esta misma cifra al abrir."
            />
          </div>

          <div className="modal__acciones">
            <Boton variante="plano" onClick={() => setPaso('contar')} disabled={cerrando}>
              Volver a contar
            </Boton>
            <Boton variante="principal" onClick={cerrar} ocupado={cerrando}
                   textoOcupado="Cerrando…">
              Cerrar la caja
            </Boton>
          </div>
        </>
      )}
    </>
  )
}

/**
 * La revelacion: lo que devolvio el servidor y nada mas.
 *
 * LA DIFERENCIA SE PRESENTA COMO UN HECHO, NO COMO UNA ACUSACION. Sin `aviso--error`,
 * sin `role="alert"`, sin icono de alarma, sin la palabra "error". Va a cerrar caja
 * una empleada y una diferencia de quinientos pesos no es un delito. Lo que si se
 * distingue es sobrante de faltante, porque eso cambia que hay que buscar.
 */
function Revelacion({ arqueo, alTerminar }) {
  const { sesion, ventasPorMetodo } = arqueo
  const diferencia = describirDiferencia(sesion.diferencia)

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Caja {sesion.consecutivo} · cerrada</h1>
      </div>

      <div className="arqueo">
        <div className="arqueo__linea">
          <span>Esperado</span>
          <span className="monto">{formatearPesos(sesion.efectivoEsperado)}</span>
        </div>
        <div className="arqueo__linea">
          <span>Contaste</span>
          <span className="monto">{formatearPesos(sesion.efectivoContado)}</span>
        </div>
        <div className={`arqueo__linea arqueo__diferencia arqueo__diferencia--${diferencia.tono}`}>
          <span>{diferencia.cuanto === undefined ? 'La caja cuadró' : diferencia.texto}</span>
          {diferencia.cuanto !== undefined && (
            <span className="monto">{formatearPesos(diferencia.cuanto)}</span>
          )}
        </div>
      </div>

      {ventasPorMetodo.length > 0 && (
        <>
          <h2>Ventas del turno</h2>
          <div className="tabla-envoltura">
            <table className="tabla">
              <thead>
                <tr>
                  <th>Método de pago</th>
                  <th className="numero">Ventas</th>
                  <th className="numero">Total</th>
                </tr>
              </thead>
              <tbody>
                {ventasPorMetodo.map((fila) => (
                  <tr key={fila.metodo}>
                    <td>{NOMBRE_DE_METODO[fila.metodo] ?? fila.metodo}</td>
                    <td className="numero monto">{fila.cantidad}</td>
                    <td className="numero monto">{formatearPesos(fila.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {sesion.diferencia !== 0 && (
        <NotaDeCierre sesion={sesion} cuanto={diferencia.cuanto} />
      )}

      <Aviso tipo="info" titulo="El respaldo quedó hecho">
        Cerrar la caja genera una copia de seguridad de la base de datos. Es el momento
        en que ocurre, y ya ocurrió.
      </Aviso>

      <div className="modal__acciones">
        <Boton variante="principal" onClick={alTerminar}>Terminar</Boton>
      </div>
    </>
  )
}

const NOMBRE_DE_METODO = {
  EFECTIVO: 'Efectivo',
  TARJETA: 'Tarjeta',
  NEQUI: 'Nequi',
  DAVIPLATA: 'Daviplata',
  TRANSFERENCIA: 'Transferencia',
}

/**
 * El campo para explicar la diferencia, con el foco puesto.
 *
 * NO ES OBLIGATORIO, y es a proposito. Pedir la nota antes —cuando todavia no se
 * sabia que iba a haber diferencia— capturaba ruido: se escribe "normal" y despues
 * aparece el faltante. Un campo obligatorio que casi siempre sobra deja de leerse en
 * serio justo el dia que importa. Aqui ya hay algo que explicar, es lo unico que
 * queda por hacer en la pantalla, y quien no quiera escribir puede seguir de largo:
 * el historial marcara esa sesion como "sin explicar", que es rendicion de cuentas
 * visible sin obligar a nadie a rellenar.
 *
 * La nota se AGREGA. No modifica la sesion, que sigue siendo inmutable: sus montos,
 * fechas y usuarios no se tocan nunca.
 */
function NotaDeCierre({ sesion, cuanto }) {
  const [texto, setTexto] = useState('')
  const [guardando, setGuardando] = useState(false)
  const [guardada, setGuardada] = useState(false)
  const [error, setError] = useState(null)
  const campo = useRef(null)

  useEffect(() => { campo.current?.focus() }, [])

  async function guardar() {
    if (!texto.trim() || guardando) return
    setGuardando(true)
    setError(null)
    try {
      await apiCaja.anotar(sesion.id, texto)
      setGuardada(true)
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  if (guardada) {
    return (
      <Aviso tipo="exito" titulo="La nota quedó guardada">
        Queda con tu nombre y la fecha, junto a la sesión {sesion.consecutivo}.
      </Aviso>
    )
  }

  return (
    <div className="caja__nota">
      <Campo
        etiqueta={`¿Qué pasó con esos ${formatearPesos(cuanto)}?`}
        ayuda="Se guarda con tu nombre y la fecha. También se puede anotar más tarde, desde el historial."
      >
        {(props) => (
          <textarea
            {...props}
            ref={campo}
            rows={2}
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
          />
        )}
      </Campo>

      {error && <AvisoDeError error={error} />}

      <div className="modal__acciones">
        <Boton variante="principal" onClick={guardar} ocupado={guardando}
               disabled={!texto.trim()}>
          Guardar nota
        </Boton>
      </div>
    </div>
  )
}
