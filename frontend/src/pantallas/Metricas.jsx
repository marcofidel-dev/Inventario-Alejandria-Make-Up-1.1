import { useEffect, useState } from 'react'
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Minus } from 'lucide-react'

import { metricas as apiMetricas } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { descripcionDe } from '../componentes/BuscadorDeVariante.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { etiqueta } from '../etiquetas.js'
import { formatearPesos } from './Catalogo.jsx'
import { hoy } from './Ventas.jsx'

/**
 * El panel de metricas. Solo la DUENA: cada numero de aqui lleva costo o margen.
 *
 * UNA SOLA LLAMADA para todo lo que se ve al abrir. Con pool de una conexion el
 * backend serializa las consultas, y nueve peticiones sueltas se sentirian lentas sin
 * que se pudiera esconder. Vencimientos (el detalle) y sin rotacion se piden aparte,
 * solo si alguien entra a mirarlos.
 *
 * LOS ERRORES DEL SERVIDOR SE MUESTRAN TAL CUAL. El limite de 366 dias, por ejemplo,
 * no se dispara con ningun periodo de hoy; si un dia se dispara, el mensaje ya viene
 * escrito para leerse y reescribirlo aqui seria mantener dos versiones.
 */

const PERIODOS = [
  { id: 'DIA', texto: 'Día' },
  { id: 'SEMANA', texto: 'Semana' },
  { id: 'MES', texto: 'Mes' },
]

/**
 * Lo que la pantalla dice sobre como se agruparon las ventas. Sale del campo
 * `agrupacion` de la respuesta y no de un supuesto: el dia que una metrica agrupe por
 * sesion de caja, la nota tiene que cambiar con ella.
 */
const NOTA_DE_AGRUPACION = {
  FECHA_DE_VENTA: 'Las ventas se agrupan por la fecha del calendario en que se hicieron, '
    + 'no por sesión de caja. Si una caja quedó abierta de un día para otro, sus ventas '
    + 'caen en dos fechas y el total de un día puede no coincidir con el cierre de esa caja.',
  SESION_DE_CAJA: 'Las ventas se agrupan por la sesión de caja que las contiene, no por '
    + 'fecha del calendario: una caja que se abrió un día y cerró al siguiente cuenta '
    + 'entera en una sola fila.',
}

export function Metricas() {
  const [periodo, setPeriodo] = useState('DIA')
  const [fecha, setFecha] = useState(hoy)
  const [panel, setPanel] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)
  const [intento, setIntento] = useState(0)

  useEffect(() => {
    let vigente = true
    setCargando(true)
    setError(null)
    apiMetricas.panel(periodo, fecha)
      .then((respuesta) => { if (vigente) setPanel(respuesta) })
      .catch((fallo) => { if (vigente) { setPanel(null); setError(fallo) } })
      .finally(() => { if (vigente) setCargando(false) })
    return () => { vigente = false }
  }, [periodo, fecha, intento])

  const siguiente = desplazar(periodo, fecha, 1)

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Métricas</h1>
      </div>

      <div className="metricas__controles">
        <div className="metricas__periodos" role="tablist" aria-label="Periodo">
          {PERIODOS.map((p) => (
            <button
              key={p.id}
              type="button"
              role="tab"
              className="metricas__periodo"
              aria-selected={periodo === p.id}
              onClick={() => setPeriodo(p.id)}
            >
              {p.texto}
            </button>
          ))}
        </div>

        <Boton variante="plano" icono={ArrowLeft} aria-label="Periodo anterior"
               onClick={() => setFecha(desplazar(periodo, fecha, -1))} />
        <Campo
          etiqueta="Fecha"
          type="date"
          value={fecha}
          onChange={(e) => setFecha(e.target.value || hoy())}
        />
        <Boton variante="plano" icono={ArrowRight} aria-label="Periodo siguiente"
               disabled={siguiente > hoy()} onClick={() => setFecha(siguiente)} />
      </div>

      {error && <AvisoDeError error={error} alReintentar={() => setIntento(intento + 1)} />}

      {cargando && <p className="texto-secundario">Cargando…</p>}

      {!cargando && panel && <PanelCompleto panel={panel} />}
    </>
  )
}

function PanelCompleto({ panel }) {
  const { actual, anterior } = panel
  // Sin ventas en el periodo anterior no hay contra que comparar: un "sube 100 %"
  // sobre cero no dice nada, y un "baja" tampoco.
  const comparable = anterior.ventas > 0

  return (
    <>
      <p className="metricas__periodo-texto">
        <strong>{rangoTexto(panel.periodo)}</strong>
        <span className="texto-secundario"> · frente a {rangoTexto(panel.periodoAnterior)}</span>
      </p>

      <div className="tarjetas">
        <Tarjeta titulo="Ingreso" cifra={pesos(actual.ingreso)}
                 variacion={comparable && variacionDe(actual.ingreso, anterior.ingreso)}
                 antes={comparable && pesos(anterior.ingreso)} />
        <Tarjeta titulo="Margen" cifra={pesos(actual.margen)}
                 variacion={comparable && variacionDe(actual.margen, anterior.margen)}
                 antes={comparable && pesos(anterior.margen)} />
        {/* NULL ES "SIN DATOS", NO CERO POR CIENTO: sin ingreso no hay sobre que
            calcular el margen, y un 0 % diria que se vendio sin ganar nada. */}
        <Tarjeta titulo="Margen %"
                 cifra={actual.margenPorcentaje === null ? '—' : `${actual.margenPorcentaje} %`}
                 nota={actual.margenPorcentaje === null ? 'sin ventas' : undefined}
                 variacion={comparable && puntosDe(actual.margenPorcentaje, anterior.margenPorcentaje)}
                 antes={comparable && anterior.margenPorcentaje !== null
                   && `${anterior.margenPorcentaje} %`} />
        <Tarjeta titulo="Ventas" cifra={String(actual.ventas)}
                 variacion={comparable && variacionDe(actual.ventas, anterior.ventas)}
                 antes={comparable && String(anterior.ventas)} />
        <Tarjeta titulo="Unidades" cifra={String(actual.unidades)}
                 variacion={comparable && variacionDe(actual.unidades, anterior.unidades)}
                 antes={comparable && String(anterior.unidades)} />
      </div>
      {!comparable && (
        <p className="texto-tenue">El periodo anterior no tuvo ventas: no hay con qué comparar.</p>
      )}

      <PorMetodo filas={panel.porMetodoPago} />
      <MasVendidos porUnidades={panel.masVendidosPorUnidades}
                   porMargen={panel.masVendidosPorMargen} />
      <Inventario inventario={panel.inventario} />
      <Vencimientos conteos={panel.vencimientos} />

      <details className="metricas__nota">
        <summary>¿Por qué un número no cuadra con el cierre de caja?</summary>
        <p>{NOTA_DE_AGRUPACION[panel.agrupacion]}</p>
      </details>
    </>
  )
}

function Tarjeta({ titulo, cifra, variacion, antes, nota, tono }) {
  return (
    <div className={tono ? `tarjeta tarjeta--${tono}` : 'tarjeta'}>
      <span className="tarjeta__titulo">{titulo}</span>
      <span className="tarjeta__cifra monto">{cifra}</span>
      {nota && <span className="tarjeta__detalle">{nota}</span>}
      {variacion && (
        <span className={`tarjeta__detalle variacion variacion--${variacion.sentido}`}>
          <variacion.icono size={14} aria-hidden="true" />
          {variacion.texto}
          {antes && <span className="texto-secundario"> · antes {antes}</span>}
        </span>
      )}
    </div>
  )
}

function PorMetodo({ filas }) {
  const mayor = Math.max(0, ...filas.map((fila) => fila.total))

  return (
    <section className="metricas__seccion">
      <h2 className="seccion__titulo">Ventas por método de pago</h2>
      {filas.length === 0 ? (
        <p className="texto-secundario">Sin ventas en este periodo.</p>
      ) : (
        <ul className="barras">
          {filas.map((fila) => (
            <li key={fila.metodo} className="barras__fila">
              <div className="barras__texto">
                <span>{etiqueta(fila.metodo)}</span>
                <span>
                  <span className="monto">{pesos(fila.total)}</span>
                  <span className="texto-secundario"> · {fila.cantidad} {fila.cantidad === 1 ? 'venta' : 'ventas'}</span>
                </span>
              </div>
              <div className="barra">
                <div className="barra__relleno"
                     style={{ width: `${mayor > 0 ? Math.max(0, (fila.total / mayor) * 100) : 0}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function MasVendidos({ porUnidades, porMargen }) {
  return (
    <section className="metricas__seccion">
      <h2 className="seccion__titulo">Lo más vendido</h2>
      <p className="texto-secundario metricas__explicacion">
        Lo que más se vende no siempre es lo que más deja: un producto barato puede
        encabezar por unidades y aportar menos margen que otro que se vende menos.
        Por eso van dos listas.
      </p>
      <div className="listas-pareja">
        <Ranking titulo="Por unidades" filas={porUnidades} />
        <Ranking titulo="Por margen aportado" filas={porMargen} />
      </div>
    </section>
  )
}

function Ranking({ titulo, filas }) {
  return (
    <div>
      <h3 className="seccion__titulo">{titulo}</h3>
      {filas.length === 0 ? (
        <p className="texto-secundario">Sin ventas en este periodo.</p>
      ) : (
        <div className="tabla-envoltura">
          <table className="tabla">
            <thead>
              <tr>
                <th>#</th>
                <th>Producto</th>
                <th className="numero">Unidades</th>
                <th className="numero">Margen</th>
              </tr>
            </thead>
            <tbody>
              {filas.map((fila, indice) => (
                <tr key={fila.varianteId}>
                  <td>{indice + 1}</td>
                  <td>{descripcionDe(fila)}</td>
                  <td className="numero monto">{fila.unidades}</td>
                  <td className="numero monto">{pesos(fila.margen)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function Inventario({ inventario }) {
  const { valorACosto, unidades, variantesBajoMinimo } = inventario

  return (
    <section className="metricas__seccion">
      <h2 className="seccion__titulo">Inventario</h2>
      <div className="tarjetas">
        <Tarjeta titulo="Valor a costo" cifra={pesos(valorACosto)} />
        <Tarjeta titulo="Unidades en stock" cifra={String(unidades)} />
      </div>

      <h3 className="seccion__titulo">Bajo el mínimo</h3>
      {variantesBajoMinimo.length === 0 ? (
        <Aviso tipo="exito">Todo por encima del mínimo.</Aviso>
      ) : (
        <div className="tabla-envoltura">
          <table className="tabla">
            <thead>
              <tr>
                <th>Producto</th>
                <th className="numero">Stock</th>
                <th className="numero">Mínimo</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {variantesBajoMinimo.map((fila) => (
                <tr key={fila.varianteId}>
                  <td>{descripcionDe(fila)}</td>
                  <td className="numero monto">{fila.stock}</td>
                  <td className="numero monto">{fila.stockMinimo}</td>
                  <td>
                    {/* Negativo manda sobre bajo: uno dice "hay que reponer" y el otro
                        "falta averiguar algo", y son dos tareas distintas. */}
                    {fila.stock < 0
                      ? <span className="insignia insignia--error">negativo</span>
                      : <span className="insignia insignia--alerta">bajo</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function Vencimientos({ conteos }) {
  const [detalle, setDetalle] = useState(null)
  const [cargando, setCargando] = useState(false)
  const [error, setError] = useState(null)
  const [abierto, setAbierto] = useState(false)

  // Se pide la primera vez que alguien abre el detalle, no antes: el panel ya trae los
  // conteos y la lista solo hace falta a quien quiere ver cuales son.
  async function alternar() {
    if (abierto) return setAbierto(false)
    setAbierto(true)
    if (detalle || cargando) return
    setCargando(true)
    setError(null)
    try {
      setDetalle(await apiMetricas.vencimientos())
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCargando(false)
    }
  }

  return (
    <section className="metricas__seccion">
      <h2 className="seccion__titulo">Vencimientos</h2>
      <div className="tarjetas">
        {/* El color solo aparece cuando hay algo que mirar: un "0" en rojo alarma sin
            motivo, y quien ya se acostumbro a ver rojo deja de mirarlo. */}
        <Tarjeta titulo="Vencidos" cifra={String(conteos.vencidos)}
                 tono={conteos.vencidos > 0 ? 'error' : undefined} />
        <Tarjeta titulo="Vencen en 30 días o menos" cifra={String(conteos.hasta30)}
                 tono={conteos.hasta30 > 0 ? 'alerta' : undefined} />
        <Tarjeta titulo="Entre 31 y 60 días" cifra={String(conteos.entre31y60)} />
        <Tarjeta titulo="Entre 61 y 90 días" cifra={String(conteos.entre61y90)} />
      </div>

      <Boton variante="plano" onClick={alternar} aria-expanded={abierto}>
        {abierto ? 'Ocultar detalle' : 'Ver detalle'}
      </Boton>

      {abierto && (
        <>
          {error && <AvisoDeError error={error} />}
          {cargando && <p className="texto-secundario">Cargando…</p>}
          {detalle && (detalle.length === 0
            ? <Aviso tipo="exito">Nada vence en los próximos 90 días.</Aviso>
            : (
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Producto</th>
                      <th>Vence</th>
                      <th>Cuándo</th>
                      <th className="numero">Stock</th>
                      <th className="numero">Valor a costo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detalle.map((fila) => (
                      <tr key={fila.varianteId}>
                        <td>{descripcionDe(fila)}</td>
                        <td>{fechaCorta(fila.fechaVencimiento)}</td>
                        <td>
                          {fila.diasParaVencer < 0
                            ? <span className="insignia insignia--error">{cuando(fila.diasParaVencer)}</span>
                            : fila.diasParaVencer <= 30
                              ? <span className="insignia insignia--alerta">{cuando(fila.diasParaVencer)}</span>
                              : cuando(fila.diasParaVencer)}
                        </td>
                        <td className="numero monto">{fila.stock}</td>
                        <td className="numero monto">{pesos(fila.valorACosto)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
        </>
      )}
    </section>
  )
}

const DIAS_POR_DEFECTO = 90

/**
 * Variantes con stock que no se han vendido en N dias.
 *
 * "Sin ventas en N dias" NO ES "nunca vendido": la lista no distingue el producto que
 * se vendia y dejo de venderse del que jamas se vendio, y son problemas distintos —uno
 * perdio su clientela, el otro nunca la tuvo—. La aclaracion la escribe el servidor y
 * se muestra tal cual; el titulo de aqui nombra lo que la lista si dice.
 */
export function SinRotacion() {
  const [texto, setTexto] = useState(String(DIAS_POR_DEFECTO))
  const [dias, setDias] = useState(DIAS_POR_DEFECTO)
  const [respuesta, setRespuesta] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)
  const [intento, setIntento] = useState(0)

  useEffect(() => {
    let vigente = true
    setCargando(true)
    setError(null)
    apiMetricas.sinRotacion(dias)
      .then((datos) => { if (vigente) setRespuesta(datos) })
      .catch((fallo) => { if (vigente) { setRespuesta(null); setError(fallo) } })
      .finally(() => { if (vigente) setCargando(false) })
    return () => { vigente = false }
  }, [dias, intento])

  const candidato = Number(texto)
  const valido = texto.trim() !== '' && Number.isInteger(candidato) && candidato >= 1

  function consultar(evento) {
    evento.preventDefault()
    if (valido) setDias(candidato)
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Sin rotación</h1>
      </div>

      <form className="metricas__controles" onSubmit={consultar}>
        <Campo
          etiqueta="Días sin ventas"
          type="number"
          min="1"
          value={texto}
          error={valido ? undefined : 'Escribe un número entero de días, de 1 en adelante.'}
          onChange={(e) => setTexto(e.target.value)}
        />
        <Boton type="submit" disabled={!valido}>Ver</Boton>
      </form>

      {error && <AvisoDeError error={error} alReintentar={() => setIntento(intento + 1)} />}
      {cargando && <p className="texto-secundario">Cargando…</p>}

      {!cargando && respuesta && (
        <>
          <h2 className="seccion__titulo">
            Con stock y sin ventas en los últimos {respuesta.dias} días
          </h2>
          {respuesta.aclaracion && <Aviso tipo="info">{respuesta.aclaracion}</Aviso>}

          {respuesta.filas.length === 0 ? (
            <Aviso tipo="exito">
              Todo lo que hay en stock se vendió en los últimos {respuesta.dias} días.
            </Aviso>
          ) : (
            <>
              <p className="texto-secundario metricas__explicacion">
                {respuesta.filas.length} {respuesta.filas.length === 1 ? 'variante' : 'variantes'} ·{' '}
                <span className="monto">
                  {pesos(respuesta.filas.reduce((suma, fila) => suma + fila.valorACosto, 0))}
                </span>{' '}
                a costo
              </p>
              <div className="tabla-envoltura">
                <table className="tabla">
                  <thead>
                    <tr>
                      <th>Producto</th>
                      <th className="numero">Stock</th>
                      <th className="numero">Costo promedio</th>
                      <th className="numero">Valor a costo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {respuesta.filas.map((fila) => (
                      <tr key={fila.varianteId}>
                        <td>{descripcionDe(fila)}</td>
                        <td className="numero monto">{fila.stock}</td>
                        <td className="numero monto">{pesos(fila.costoPromedio)}</td>
                        <td className="numero monto">{pesos(fila.valorACosto)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </>
  )
}

// ------------------------------------------------------------------ ayudas

const pesos = (monto) => `$ ${formatearPesos(monto)}`

/**
 * Cuanto cambio una cifra frente a la del periodo anterior, o false si no se puede
 * decir: sobre una base en cero un porcentaje no existe.
 */
function variacionDe(actual, anterior) {
  if (anterior === 0) return false
  const porcentaje = Math.round(((actual - anterior) / Math.abs(anterior)) * 100)
  return conSentido(porcentaje, `${Math.abs(porcentaje)} %`)
}

/** El margen porcentual ya es un porcentaje: se compara en puntos, no en "por ciento del por ciento". */
function puntosDe(actual, anterior) {
  if (actual === null || anterior === null) return false
  const puntos = actual - anterior
  return conSentido(puntos, `${Math.abs(puntos)} ${Math.abs(puntos) === 1 ? 'punto' : 'puntos'}`)
}

function conSentido(cambio, texto) {
  if (cambio > 0) return { sentido: 'sube', icono: ArrowUp, texto: `Sube ${texto}` }
  if (cambio < 0) return { sentido: 'baja', icono: ArrowDown, texto: `Baja ${texto}` }
  return { sentido: 'igual', icono: Minus, texto: 'Sin cambio' }
}

/** Una fecha ISO como fecha local, sin pasar por la zona horaria. */
function fechaLocal(iso) {
  const [anio, mes, dia] = iso.split('-').map(Number)
  return new Date(anio, mes - 1, dia)
}

function aIso(fecha) {
  const mes = String(fecha.getMonth() + 1).padStart(2, '0')
  const dia = String(fecha.getDate()).padStart(2, '0')
  return `${fecha.getFullYear()}-${mes}-${dia}`
}

/**
 * El inicio del periodo siguiente (+1) o anterior (-1) al que contiene `iso`.
 * Se parte del inicio del periodo y no de la fecha: un 31 de enero mas un mes seria
 * 3 de marzo, y saltaria febrero.
 */
function desplazar(periodo, iso, sentido) {
  const fecha = fechaLocal(iso)
  if (periodo === 'MES') return aIso(new Date(fecha.getFullYear(), fecha.getMonth() + sentido, 1))
  if (periodo === 'SEMANA') {
    const lunes = new Date(fecha.getFullYear(), fecha.getMonth(),
      fecha.getDate() - ((fecha.getDay() + 6) % 7))
    return aIso(new Date(lunes.getFullYear(), lunes.getMonth(), lunes.getDate() + 7 * sentido))
  }
  return aIso(new Date(fecha.getFullYear(), fecha.getMonth(), fecha.getDate() + sentido))
}

const FORMATO_FECHA = new Intl.DateTimeFormat('es-CO', { day: 'numeric', month: 'short', year: 'numeric' })

const fechaCorta = (iso) => FORMATO_FECHA.format(fechaLocal(iso))

function rangoTexto({ desde, hasta }) {
  return desde === hasta ? fechaCorta(desde) : `${fechaCorta(desde)} – ${fechaCorta(hasta)}`
}

function cuando(dias) {
  if (dias < 0) return `hace ${-dias} ${dias === -1 ? 'día' : 'días'}`
  if (dias === 0) return 'hoy'
  return `en ${dias} ${dias === 1 ? 'día' : 'días'}`
}
