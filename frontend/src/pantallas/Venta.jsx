import { useEffect, useMemo, useRef, useState } from 'react'
import { Trash2 } from 'lucide-react'

import { CODIGOS } from '../api/cliente.js'
import { caja as apiCaja, ventas as apiVentas } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { BuscadorDeVariante, descripcionDe } from '../componentes/BuscadorDeVariante.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { etiqueta } from '../etiquetas.js'
import { useRecibo } from '../ventas/useRecibo.js'
import { formatearPesos } from './Catalogo.jsx'

/**
 * El mostrador.
 *
 * TODO CON TECLADO. Buscar, elegir, cantidad, siguiente producto, cobrar: sin tocar
 * el raton en ningun momento. El foco vuelve solo al buscador despues de cada linea,
 * porque el gesto que se repite doscientas veces al dia es "buscar el siguiente
 * producto" y no "volver a poner el cursor donde estaba".
 *
 * FALLAR TEMPRANO, NO EN LA CAJA. Los dos motivos por los que un cobro se cae —no hay
 * caja abierta, o la variante no tiene costo— se detectan antes: el primero al entrar,
 * el segundo al agregar la linea. Ninguno de los dos puede aparecer con el carrito
 * lleno y una clienta enfrente, que es donde no hay salida buena.
 */
export function Venta({ catalogo, alIrA }) {
  const [sesion, setSesion] = useState(null)
  const [cargandoSesion, setCargandoSesion] = useState(true)
  const [errorDeSesion, setErrorDeSesion] = useState(null)

  const [lineas, setLineas] = useState([])
  const [metodoPago, setMetodoPago] = useState('EFECTIVO')
  const [recibido, setRecibido] = useState('')
  const [rechazo, setRechazo] = useState(null)
  const [cobrando, setCobrando] = useState(false)
  const [error, setError] = useState(null)
  const [ultima, setUltima] = useState(null)

  const contenedor = useRef(null)

  /**
   * EL UUID DE ESTE CARRITO.
   *
   * Se genera al abrir el carrito, SOBREVIVE A LOS FALLOS y solo se descarta cuando un
   * cobro sale bien. Esa es toda la idempotencia del sistema: si la respuesta se pierde
   * —red, timeout, el equipo que se reinicia— la venta puede haber quedado escrita
   * entera del otro lado, y el reintento tiene que llevar el MISMO uuid para que el
   * servidor devuelva la que ya existe en vez de cobrar de nuevo, descontar el
   * inventario de nuevo y meter la plata en el cajon de nuevo.
   *
   * Va en un ref y no en estado por dos razones: no se pinta, y StrictMode invoca dos
   * veces las funciones actualizadoras — un uuid generado dentro de un setEstado
   * saldria distinto en cada pasada.
   */
  const uuid = useRef(nuevoUuid())

  /**
   * Si el intento anterior de ESTE carrito fallo.
   *
   * Decide si un 200 es normal o alarmante. El servidor responde 200 cuando ya tenia
   * una venta con este uuid: despues de un fallo eso es exactamente la recuperacion
   * que se busca y no hay nada que decir. Sin fallo previo significa que el uuid se
   * reutilizo —un cobro se esta tragando en silencio— y eso si hay que verlo.
   */
  const huboFallo = useRef(false)

  useEffect(() => { comprobarLaCaja() }, [])

  async function comprobarLaCaja() {
    setCargandoSesion(true)
    setErrorDeSesion(null)
    try {
      // Un 404 no es un fallo: es "no hay caja abierta", que es el estado normal de
      // la manana. Cualquier otro error si lo es.
      setSesion(await apiCaja.sesionActual().catch((fallo) => {
        if (fallo.codigo === CODIGOS.noEncontrado) return null
        throw fallo
      }))
    } catch (fallo) {
      setErrorDeSesion(fallo)
    } finally {
      setCargandoSesion(false)
    }
  }

  const total = useMemo(
    () => lineas.reduce((suma, l) => suma + Number(l.cantidad || 0) * l.precioUnitario, 0),
    [lineas],
  )

  const enEfectivo = metodoPago === 'EFECTIVO'
  const recibidoNumero = Number(recibido || 0)
  const alcanza = !enEfectivo || (recibido !== '' && recibidoNumero >= total)
  const cambioEnPantalla = enEfectivo && alcanza ? recibidoNumero - total : null
  const sePuedeCobrar = lineas.some((l) => Number(l.cantidad) > 0) && alcanza && !cobrando

  function enfocar(selector) {
    requestAnimationFrame(() => contenedor.current?.querySelector(selector)?.focus())
  }

  /** El método que está marcado, o el primero. Sin `:checked` en el selector: la marca
   *  la pone React como propiedad y no como atributo. */
  function enfocarElMetodoDePago() {
    requestAnimationFrame(() => {
      const opciones = [...(contenedor.current
        ?.querySelectorAll('input[name="metodo-de-pago"]') ?? [])]
      const elegido = opciones.find((opcion) => opcion.checked) ?? opciones[0]
      elegido?.focus()
    })
  }

  /**
   * Agrega una linea, o suma uno si esa variante ya esta en el carrito.
   *
   * EL RECHAZO POR FALTA DE COSTO OCURRE AQUI. La bandera viene del catalogo
   * (`sinCosto`), asi que no hace falta preguntarle al servidor: la variante ni siquiera
   * entra. Si esto se dejara para el cobro, el 409 llegaria con el carrito armado, y
   * quien atiende tendria que deshacerlo delante de la clienta.
   */
  function agregar(fila) {
    if (!fila) return

    if (fila.sinCosto) {
      setRechazo(`«${descripcionDe(fila)}» todavía no se puede vender: no tiene costo `
        + 'registrado. Hay que recibir la compra pendiente de ese producto —o cargarlo '
        + 'en las existencias iniciales— y volver a cobrarlo.')
      return
    }

    setRechazo(null)
    setUltima(null)
    setLineas((actuales) => {
      const yaEsta = actuales.findIndex((l) => l.varianteId === fila.id)
      if (yaEsta >= 0) {
        return actuales.map((l, i) => (
          i === yaEsta ? { ...l, cantidad: String(Number(l.cantidad || 0) + 1) } : l
        ))
      }
      return [...actuales, {
        varianteId: fila.id,
        descripcion: descripcionDe(fila),
        precioUnitario: fila.precioVenta,
        cantidad: '1',
      }]
    })
    enfocar(`input[data-cantidad="${fila.id}"]`)
  }

  async function cobrar() {
    if (!sePuedeCobrar) return
    setCobrando(true)
    setError(null)
    try {
      const { estado, datos } = await apiVentas.cobrar({
        uuid: uuid.current,
        metodoPago,
        efectivoRecibido: enEfectivo ? recibidoNumero : null,
        lineas: lineas
          .filter((l) => Number(l.cantidad) > 0)
          .map((l) => ({ varianteId: l.varianteId, cantidad: Number(l.cantidad) })),
      })

      // El stock en memoria baja con lo que el SERVIDOR dice que salio, no con lo que
      // la pantalla tenia en el carrito.
      catalogo.descontarStock(datos.lineas)

      setUltima({
        venta: datos,
        // Un 200 tras un fallo es la recuperacion esperada; sin fallo previo es un
        // uuid reutilizado, y eso hay que verlo.
        uuidReutilizado: estado === 200 && !huboFallo.current,
        cambioQueMostroLaPantalla: cambioEnPantalla,
      })

      uuid.current = nuevoUuid()
      huboFallo.current = false
      setLineas([])
      setRecibido('')
      setMetodoPago('EFECTIVO')
      enfocar('input[data-buscador]')
    } catch (fallo) {
      // El uuid NO se toca: el reintento tiene que ser la misma peticion. Y el carrito
      // se queda como estaba, para que reintentar sea pulsar una vez.
      huboFallo.current = true
      setError(fallo)
    } finally {
      setCobrando(false)
    }
  }

  if (cargandoSesion) return <p className="texto-secundario">Abriendo el mostrador…</p>

  if (errorDeSesion) {
    return (
      <>
        <div className="pantalla__cabecera"><h1>Vender</h1></div>
        <AvisoDeError error={errorDeSesion} alReintentar={comprobarLaCaja} />
      </>
    )
  }

  // SIN CAJA NO SE ARMA CARRITO. No es un aviso que se pueda ignorar mientras se van
  // metiendo productos: no hay debajo nada mas que hacer. Dejar armar el carrito seria
  // pedirle a alguien que haga un trabajo que se va a perder entero en el ultimo paso.
  if (!sesion || sesion.esDeUnDiaAnterior) {
    return <SinCajaParaVender sesion={sesion} alIrA={alIrA} />
  }

  return (
    <div ref={contenedor}>
      <div className="pantalla__cabecera">
        <h1>Vender</h1>
        <span className="texto-secundario">
          {sesion.consecutivo} · <span className="insignia insignia--exito">caja abierta</span>
        </span>
      </div>

      {ultima && <Comprobante {...ultima} />}

      <div className="venta">
        <div className="venta__carrito">
          {/* `filas` y no `todas`: no se puede vender lo que nunca entro. La lista
              corta ya deja fuera las variantes sin ningun movimiento. */}
          <div onKeyDown={alTeclearEnElBuscador}>
            <BuscadorDeVariante
              filas={catalogo.filas}
              valor=""
              etiqueta="Buscar producto"
              etiquetaAccesible="Buscar producto"
              alElegir={(id) => agregar(catalogo.filas.find((f) => String(f.id) === String(id)))}
            />
          </div>

          {rechazo && <Aviso tipo="error" titulo="Ese producto todavía no se puede vender">
            {rechazo}
          </Aviso>}

          {lineas.length === 0
            ? (
              <div className="estado-vacio">
                <p>El carrito está vacío. Busca el primer producto y elígelo con Enter.</p>
              </div>
            )
            : <Carrito lineas={lineas} alCambiar={cambiarLinea} alQuitar={quitarLinea}
                       alTerminarCantidad={() => enfocar('input[data-buscador]')} />}

          <p className="carga__pista">
            Enter elige el producto de la lista. Enter en la cantidad vuelve al buscador.
            Enter en el buscador vacío pasa al cobro.
          </p>
        </div>

        <div className="venta__cobro">
          <p className="venta__total">
            <span className="texto-secundario">A cobrar</span>
            <span className="monto venta__cifra" aria-live="polite">{formatearPesos(total)}</span>
          </p>

          {error && <AvisoDeError error={error} />}

          <fieldset className="formulario">
            <legend>Cómo paga</legend>
            {METODOS.map((metodo) => (
              <label key={metodo} className="opcion">
                <input
                  type="radio"
                  name="metodo-de-pago"
                  value={metodo}
                  checked={metodoPago === metodo}
                  onChange={() => { setMetodoPago(metodo); setRecibido('') }}
                />
                <span>{etiqueta(metodo)}</span>
              </label>
            ))}
          </fieldset>

          {enEfectivo && (
            <Efectivo
              total={total}
              recibido={recibido}
              alCambiar={setRecibido}
              cambio={cambioEnPantalla}
              alCobrar={cobrar}
            />
          )}

          <Boton variante="principal" onClick={cobrar} disabled={!sePuedeCobrar}
                 ocupado={cobrando} textoOcupado="Cobrando…">
            Cobrar
          </Boton>
        </div>
      </div>
    </div>
  )

  function cambiarLinea(varianteId, cantidad) {
    setUltima(null)
    setLineas((actuales) => actuales.map((l) => (
      l.varianteId === varianteId ? { ...l, cantidad } : l
    )))
  }

  function quitarLinea(varianteId) {
    setLineas((actuales) => actuales.filter((l) => l.varianteId !== varianteId))
  }

  /**
   * Enter en el buscador VACIO pasa al cobro. Con texto escrito no se toca: ahi Enter
   * es "elige la sugerencia resaltada", que es el gesto de cada linea.
   */
  function alTeclearEnElBuscador(evento) {
    if (evento.key !== 'Enter' || evento.target.value !== '') return
    if (lineas.length === 0) return
    evento.preventDefault()
    enfocarElMetodoDePago()
  }
}

// ------------------------------------------------------------------ sin caja

/**
 * Lo que se ve cuando no hay donde meter la venta. Dice que falta y lleva al sitio
 * donde se arregla, en vez de dejar a alguien mirando una pantalla que no responde.
 */
function SinCajaParaVender({ sesion, alIrA }) {
  const olvidada = Boolean(sesion)

  return (
    <>
      <div className="pantalla__cabecera"><h1>Vender</h1></div>

      <Aviso
        tipo="alerta"
        titulo={olvidada
          ? `Quedó abierta la caja del ${soloFecha(sesion.fechaApertura)}`
          : 'Todavía no se ha abierto la caja'}
      >
        {olvidada
          ? 'Hasta cerrarla no se puede vender: lo de hoy entraría en el arqueo de ese día '
            + 'y el descuadre no se vería, porque esa sesión cuadra consigo misma.'
          : 'Ninguna venta existe fuera de una sesión de caja abierta. Es lo primero del día.'}
      </Aviso>

      <div className="modal__acciones">
        <Boton variante="principal" onClick={() => alIrA?.({ seccion: 'caja', pestana: null })}>
          {olvidada ? 'Ir a cerrar esa caja' : 'Ir a abrir la caja'}
        </Boton>
      </div>
    </>
  )
}

// ------------------------------------------------------------------- carrito

function Carrito({ lineas, alCambiar, alQuitar, alTerminarCantidad }) {
  return (
    <div className="tabla-envoltura">
      <table className="tabla">
        <thead>
          <tr>
            <th>Producto</th>
            <th className="numero">Cantidad</th>
            <th className="numero">Precio</th>
            <th className="numero">Subtotal</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {lineas.map((linea) => (
            <tr key={linea.varianteId}>
              <td>{linea.descripcion}</td>
              <td className="carga__celda-numero">
                <Campo etiqueta="">
                  {(props) => (
                    <input
                      {...props}
                      data-cantidad={linea.varianteId}
                      inputMode="numeric"
                      value={linea.cantidad}
                      aria-label={`Cantidad de ${linea.descripcion}`}
                      onChange={(e) => alCambiar(linea.varianteId,
                        e.target.value.replace(/\D/g, ''))}
                      onKeyDown={(e) => {
                        if (e.key !== 'Enter') return
                        e.preventDefault()
                        alTerminarCantidad()
                      }}
                    />
                  )}
                </Campo>
              </td>
              <td className="numero monto">{formatearPesos(linea.precioUnitario)}</td>
              <td className="numero monto">
                {formatearPesos(Number(linea.cantidad || 0) * linea.precioUnitario)}
              </td>
              <td>
                <Boton
                  variante="plano"
                  icono={Trash2}
                  aria-label={`Quitar ${linea.descripcion}`}
                  onClick={() => alQuitar(linea.varianteId)}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ------------------------------------------------------------------ efectivo

/** El orden en que se ofrecen. El nombre legible lo pone `etiqueta()`, que es el
    mismo que usa el listado de ventas y el cierre de caja. */
const METODOS = ['EFECTIVO', 'TARJETA', 'NEQUI', 'DAVIPLATA', 'TRANSFERENCIA']

/**
 * Con cuanto paga y cuanto se le devuelve.
 *
 * Los botones son los billetes con los que de verdad se paga: el exacto, el siguiente
 * mil y los redondeos de arriba. Se calculan sobre el total, no son una lista fija:
 * para 102.300 lo util es 103.000 y 110.000, no "50.000, 100.000".
 */
function Efectivo({ total, recibido, alCambiar, cambio, alCobrar }) {
  const sugerencias = sugerenciasDeEfectivo(total)

  return (
    <div className="venta__efectivo">
      <span className="campo__etiqueta">¿Con cuánto paga?</span>
      <div className="venta__sugerencias">
        {sugerencias.map((monto) => (
          <Boton key={monto} onClick={() => alCambiar(String(monto))}>
            {formatearPesos(monto)}
          </Boton>
        ))}
      </div>

      <Campo
        etiqueta="Otro monto"
        inputMode="numeric"
        value={recibido}
        onChange={(e) => alCambiar(e.target.value.replace(/\D/g, ''))}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); alCobrar() } }}
      />

      <p className="venta__total">
        <span className="texto-secundario">Cambio</span>
        <span className="monto venta__cifra-media" aria-live="polite">
          {cambio === null ? '—' : formatearPesos(cambio)}
        </span>
      </p>
    </div>
  )
}

/**
 * Con cuanto se paga un total: exacto, siguiente mil y los redondeos de arriba.
 *
 * Funcion pura y exportada para poder probarla sola. Los duplicados se quitan —para un
 * total de 100.000 todos los redondeos dan lo mismo— y nunca se ofrece menos que el
 * total, que no seria pagar.
 */
export function sugerenciasDeEfectivo(total) {
  if (total <= 0) return []
  const pasos = [1000, 5000, 10000, 20000, 50000]
  const montos = [total, ...pasos.map((paso) => Math.ceil(total / paso) * paso)]
  return [...new Set(montos)].filter((monto) => monto >= total).sort((a, b) => a - b).slice(0, 4)
}

// --------------------------------------------------------------- comprobante

/**
 * Lo que queda despues de cobrar. NO ES UN MODAL, y eso es la mitad del diseno de esta
 * pantalla: un modal que exige un clic para continuar son cientos de clics al dia, y
 * la pantalla ya esta limpia y lista para la clienta siguiente.
 *
 * EL CAMBIO QUE SE MUESTRA ES EL DEL SERVIDOR. Si difiere del que mostro la pantalla se
 * dice, en vez de taparlo: los dos numeros salen de la misma resta y que no coincidan
 * significa que el total que se cobro no era el que se estaba viendo.
 */
function Comprobante({ venta, uuidReutilizado, cambioQueMostroLaPantalla }) {
  // "Ver recibo" solo si el PDF existe. Ofrecerlo para que despues conteste que no hay
  // ninguno es peor que no ofrecerlo, y aqui hay una clienta esperando.
  const recibo = useRecibo()
  const discrepa = venta.cambio !== null && cambioQueMostroLaPantalla !== null
    && venta.cambio !== cambioQueMostroLaPantalla

  return (
    <>
      <Aviso
        tipo="exito"
        titulo={`${venta.consecutivo} cobrada`}
        accion={venta.rutaRecibo
          ? { texto: 'Ver recibo', alPulsar: () => recibo.abrir(venta.id) }
          : undefined}
      >
        <span className="monto">{formatearPesos(venta.total)}</span>
        {venta.cambio !== null && (
          <>
            {' · cambio '}
            <span className="monto venta__cifra-media">{formatearPesos(venta.cambio)}</span>
          </>
        )}
      </Aviso>

      {/* En tono de alerta y no de error: la venta salio bien y la plata entro. Lo
          que fallo es un papel que se puede volver a sacar. */}
      {recibo.error && (
        <Aviso tipo="alerta" titulo="No se pudo abrir el recibo">{recibo.error}</Aviso>
      )}

      {discrepa && (
        <Aviso tipo="alerta" titulo="El cambio no coincide con el que mostró la pantalla">
          La pantalla calculó {formatearPesos(cambioQueMostroLaPantalla)} y el servidor
          guardó {formatearPesos(venta.cambio)}. Vale el del servidor, que es el que quedó
          en el recibo. Hay que avisar: es un error del programa, no de quien cobró.
        </Aviso>
      )}

      {uuidReutilizado && (
        <Aviso tipo="alerta" titulo="Esta venta ya estaba registrada">
          El servidor devolvió la venta {venta.consecutivo} en vez de crear una nueva, y
          este cobro no falló antes. Si eran dos clientas distintas, la segunda no quedó
          cobrada: hay que revisarlo antes de entregar el producto.
        </Aviso>
      )}

      {/* Vender sin stock no se bloquea, pero tampoco pasa en silencio. Va en tono de
          alerta y no de error, y despues del aviso de exito: la venta salio bien, lo
          que hay es algo que averiguar. */}
      {venta.variantesEnNegativo?.length > 0 && (
        <Aviso tipo="alerta" titulo="Quedó stock en negativo">
          {venta.variantesEnNegativo
            .map((v) => `${v.descripcion} (${v.stock})`)
            .join(', ')}
          . Se vendió más de lo que el sistema tenía registrado, así que hay algo que
          averiguar en el inventario.
        </Aviso>
      )}
    </>
  )
}

// ---------------------------------------------------------------------- apoyo

/** El id del carrito. randomUUID donde exista; si no, hora y azar, que basta. */
function nuevoUuid() {
  return globalThis.crypto?.randomUUID?.()
    ?? `v-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

// La fecha llega como TEXT ISO de ancho fijo. Se corta por posicion y no se parsea a
// Date, que interpreta la zona horaria distinto segun el equipo.
const soloFecha = (fecha) => (fecha ?? '').slice(0, 10)
