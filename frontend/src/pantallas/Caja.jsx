import { useEffect, useState } from 'react'

import { CODIGOS } from '../api/cliente.js'
import { caja as apiCaja } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'
import { CerrarCaja, describirDiferencia, sinExplicar } from './CerrarCaja.jsx'
import { formatearPesos } from './Catalogo.jsx'

/**
 * La caja del dia.
 *
 * NINGUN MONTO DE LA SESION ABIERTA APARECE AQUI. Ni en la lista de movimientos, ni
 * como total, ni en un aviso. El descarte ocurre en api/endpoints.js, antes de que
 * esta pantalla vea nada, asi que no es que se omita al pintar: es que no llega. Lo
 * que si se muestra es QUE movimientos hubo —tipo, concepto, hora, quien— y cuantos
 * son, que es lo que sirve para verificar sin revelar el efectivo esperado.
 *
 * LOS TIPOS SE NOMBRAN POR LO QUE SE HACE. "RETIRO", "GASTO" e "INGRESO" no
 * significan nada para quien no lleva libros, y quien opera esta caja no lleva
 * libros. El nombre tecnico va debajo, en pequeno, porque es el que va a aparecer en
 * cualquier reporte y en algun momento hay que poder relacionarlos.
 */
export function Caja() {
  const [sesion, setSesion] = useState(null)
  const [movimientos, setMovimientos] = useState([])
  const [historial, setHistorial] = useState([])
  const [cargando, setCargando] = useState(true)
  const [error, setError] = useState(null)
  const [cerrando, setCerrando] = useState(false)
  const [registrando, setRegistrando] = useState(false)

  async function recargar() {
    setCargando(true)
    setError(null)
    try {
      // Un 404 en la sesion actual NO es un fallo: es "no hay caja abierta", que es
      // un estado normal de la manana. Cualquier otro error si lo es.
      const abierta = await apiCaja.sesionActual().catch((fallo) => {
        if (fallo.codigo === CODIGOS.noEncontrado) return null
        throw fallo
      })

      setSesion(abierta)
      setHistorial(await apiCaja.listar())
      setMovimientos(abierta ? await apiCaja.movimientos(abierta.id) : [])
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCargando(false)
    }
  }

  useEffect(() => { recargar() }, [])

  if (cargando) return <p className="texto-secundario">Abriendo la caja…</p>

  if (error) {
    return (
      <>
        <div className="pantalla__cabecera"><h1>Caja</h1></div>
        <AvisoDeError error={error} alReintentar={recargar} />
      </>
    )
  }

  if (cerrando) {
    return (
      <CerrarCaja
        sesion={sesion}
        alCancelar={() => setCerrando(false)}
        alTerminar={() => { setCerrando(false); recargar() }}
      />
    )
  }

  // LA CAJA OLVIDADA BLOQUEA TODO LO DEMAS. No es un aviso que se pueda ignorar: no
  // hay debajo ni movimientos ni historial ni nada mas que hacer. Si se pudiera
  // seguir operando, las ventas y los gastos de hoy entrarian en el arqueo de ayer y
  // el descuadre no se veria: la sesion cuadraria consigo misma, solo que abarcando
  // dos dias.
  if (sesion?.esDeUnDiaAnterior) {
    return <CajaOlvidada sesion={sesion} alCerrar={() => setCerrando(true)} />
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Caja</h1>
        {sesion && (
          <span className="texto-secundario">
            {sesion.consecutivo} · <span className="insignia insignia--exito">abierta</span>
          </span>
        )}
      </div>

      {sesion ? (
        <>
          <p className="texto-secundario">
            Abrió {sesion.usuarioApertura} a las {soloHora(sesion.fechaApertura)}
            {' · '}
            {sesion.cantidadDeMovimientos === 1
              ? '1 movimiento'
              : `${sesion.cantidadDeMovimientos} movimientos`}
          </p>

          <div className="caja__seccion">
            <div className="pantalla__cabecera">
              <h2>Movimientos del turno</h2>
              <Boton onClick={() => setRegistrando(true)}>Registrar movimiento</Boton>
            </div>
            <ListaDeMovimientos movimientos={movimientos} />
          </div>

          {/* Cerrar va aparte y con su propio peso. Registrar un movimiento pasa
              varias veces al dia y no tiene consecuencias; cerrar pasa una vez y es
              irreversible. Con el mismo aspecto y a dos centimetros, el error es
              cuestion de tiempo. */}
          <div className="caja__cierre">
            <div>
              <strong>Cerrar la caja es definitivo</strong>
              <p className="texto-secundario">
                Se cuenta el efectivo y la sesión queda inmutable. Se hace una vez al día,
                al terminar.
              </p>
            </div>
            <Boton variante="principal" onClick={() => setCerrando(true)}>Cerrar la caja</Boton>
          </div>
        </>
      ) : (
        <AbrirCaja alAbrir={recargar} />
      )}

      <Historial sesiones={historial} />

      {registrando && (
        <RegistrarMovimiento
          alCerrar={() => setRegistrando(false)}
          alRegistrar={() => { setRegistrando(false); recargar() }}
        />
      )}
    </>
  )
}

// --------------------------------------------------------------- caja olvidada

function CajaOlvidada({ sesion, alCerrar }) {
  return (
    <>
      <div className="pantalla__cabecera"><h1>Caja</h1></div>

      <Aviso tipo="alerta" titulo={`Quedó abierta la caja del ${soloFecha(sesion.fechaApertura)}`}>
        La abrió {sesion.usuarioApertura} a las {soloHora(sesion.fechaApertura)} y nunca se
        cerró. Hasta cerrarla no se puede vender ni registrar movimientos: lo de hoy
        entraría en el arqueo de ese día.
      </Aviso>

      <div className="modal__acciones">
        <Boton variante="principal" onClick={alCerrar}>
          Cerrar la caja del {soloFecha(sesion.fechaApertura)}
        </Boton>
      </div>
    </>
  )
}

// ----------------------------------------------------------------- abrir caja

/**
 * Abrir. La base viene prellenada con la que dejo el ultimo cierre, que es lo que la
 * cajera iba a escribir de todos modos.
 *
 * Si no hay cierre previo el campo queda EN BLANCO. El servidor manda 0 en ese caso,
 * y prellenar con un cero invita a aceptarlo sin mirar: es distinto "no hay base
 * anterior, decide tu" de "la base anterior fue cero".
 *
 * Sin campo de observaciones: sesion_caja.observaciones quedo en desuso desde V6. Lo
 * que se escribe hoy son notas, que se agregan cuando ya hay algo que contar.
 */
function AbrirCaja({ alAbrir }) {
  const [base, setBase] = useState('')
  const [origen, setOrigen] = useState(null)
  const [abriendo, setAbriendo] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let vigente = true
    apiCaja.sugerenciaDeApertura()
      .then((sugerencia) => {
        if (!vigente) return
        if (sugerencia.baseSugerida > 0) setBase(String(sugerencia.baseSugerida))
        setOrigen(sugerencia.origen)
      })
      // Un 409 aqui significa que ya hay sesion abierta, y entonces esta pantalla no
      // se esta mostrando. Nada que decir.
      .catch(() => {})
    return () => { vigente = false }
  }, [])

  async function abrir() {
    setAbriendo(true)
    setError(null)
    try {
      await apiCaja.abrir(Number(base) || 0)
      await alAbrir()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setAbriendo(false)
    }
  }

  return (
    <div className="caja__seccion">
      <h2>Abrir la caja</h2>
      <p className="texto-secundario">
        Lo primero del día. Sin caja abierta no se puede vender ni registrar nada.
      </p>

      {error && <AvisoDeError error={error} />}

      <div className="formulario formulario--angosto">
        <Campo
          etiqueta="Base inicial"
          inputMode="numeric"
          value={base}
          onChange={(e) => setBase(e.target.value.replace(/\D/g, ''))}
          ayuda={origen ?? undefined}
        />
        <div className="modal__acciones">
          <Boton variante="principal" onClick={abrir} ocupado={abriendo} textoOcupado="Abriendo…">
            Abrir la caja
          </Boton>
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- movimientos

/** Que significa cada tipo, dicho como se dice en una tienda. */
const TIPOS = [
  {
    id: 'RETIRO',
    texto: 'Sacar plata de la caja',
    detalle: 'para consignar o guardar',
    concepto: '¿Para qué se saca?',
  },
  {
    id: 'GASTO',
    texto: 'Pagar algo con plata de la caja',
    detalle: null,
    concepto: '¿En qué se gastó?',
  },
  {
    id: 'INGRESO',
    texto: 'Meter plata a la caja',
    detalle: 'sencillo para vueltos',
    concepto: '¿De dónde viene?',
  },
]

const POR_ID = new Map(TIPOS.map((tipo) => [tipo.id, tipo]))

function ListaDeMovimientos({ movimientos }) {
  if (movimientos.length === 0) {
    return (
      <div className="estado-vacio">
        <p>Todavía no hay movimientos en este turno.</p>
      </div>
    )
  }

  return (
    <div className="tabla-envoltura">
      <table className="tabla">
        <thead>
          <tr>
            <th>Hora</th>
            <th>Qué se hizo</th>
            <th>Concepto</th>
            <th>Quién</th>
          </tr>
        </thead>
        <tbody>
          {/* Sin columna de monto, y sin fila de total. A proposito: ver el detalle
              de la ultima hoja del ledger es lo que permite reconstruir el efectivo
              esperado, que es justo lo que el cierre a ciegas oculta. */}
          {movimientos.map((movimiento) => (
            <tr key={movimiento.id}>
              <td>{soloHora(movimiento.fecha)}</td>
              <td>
                {POR_ID.get(movimiento.tipo)?.texto ?? movimiento.tipo}
                <div className="texto-tenue">{movimiento.tipo}</div>
              </td>
              <td>{movimiento.concepto}</td>
              <td>{movimiento.usuario}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RegistrarMovimiento({ alCerrar, alRegistrar }) {
  const [tipo, setTipo] = useState('')
  const [monto, setMonto] = useState('')
  const [concepto, setConcepto] = useState('')
  const [guardando, setGuardando] = useState(false)
  const [error, setError] = useState(null)

  const elegido = POR_ID.get(tipo)

  async function registrar() {
    if (!tipo || !monto || !concepto.trim()) return
    setGuardando(true)
    setError(null)
    try {
      // La respuesta viene sin monto y ademas no se guarda: el importe que se acaba
      // de teclear no sobrevive a este await. Se limpian los campos antes de cerrar
      // para que tampoco quede en el DOM.
      await apiCaja.registrarMovimiento({ tipo, monto: Number(monto), concepto: concepto.trim() })
      setMonto('')
      setConcepto('')
      await alRegistrar()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  return (
    <Modal titulo="Registrar un movimiento" alCerrar={alCerrar}>
      <fieldset className="formulario">
        <legend>¿Qué vas a hacer?</legend>
        {/* Sin opcion marcada: es una pregunta, no un valor por defecto. */}
        {TIPOS.map((opcion) => (
          <label key={opcion.id} className="opcion">
            <input
              type="radio"
              name="tipo-de-movimiento"
              value={opcion.id}
              checked={tipo === opcion.id}
              onChange={() => setTipo(opcion.id)}
            />
            <span>
              {opcion.texto}
              <span className="texto-tenue">
                {opcion.detalle ? ` ${opcion.detalle} · ` : ' '}{opcion.id}
              </span>
            </span>
          </label>
        ))}
      </fieldset>

      {error && <AvisoDeError error={error} />}

      <div className="formulario">
        <Campo
          etiqueta="Monto"
          inputMode="numeric"
          value={monto}
          onChange={(e) => setMonto(e.target.value.replace(/\D/g, ''))}
        />
        <Campo
          etiqueta={elegido?.concepto ?? 'Concepto'}
          value={concepto}
          onChange={(e) => setConcepto(e.target.value)}
          disabled={!tipo}
          ayuda="Obligatorio. Un retiro sin explicación es un descuadre esperando el fin de mes."
        />
      </div>

      <div className="modal__acciones">
        <Boton variante="plano" onClick={alCerrar} disabled={guardando}>Cancelar</Boton>
        <Boton variante="principal" onClick={registrar} ocupado={guardando}
               textoOcupado="Registrando…" disabled={!tipo || !monto || !concepto.trim()}>
          Registrar
        </Boton>
      </div>
    </Modal>
  )
}

// ------------------------------------------------------------------ historial

/**
 * Cuantas sesiones cerradas se ven sin pedirlo. Al ano son unas trescientas: sin
 * tope, lo unico accionable —la sesion en curso— queda arriba de un scroll
 * interminable.
 */
const SESIONES_EN_LINEA = 10

/**
 * El historial.
 *
 * SE PIDE, NO SE FILTRA. GET /caja/sesiones ya devuelve lo que a esta persona le
 * corresponde: todas si tiene VER_SESIONES_DE_OTROS, solo las suyas si no. Filtrar
 * aqui por nombre de usuario seria mantener una segunda copia de la tabla de
 * permisos, esperando el dia en que las dos discrepen.
 *
 * LAS FILAS NO LLEVAN base_siguiente, y eso no es un olvido. El baseSiguiente de la
 * ultima sesion cerrada ES el base_inicial de la que esta en curso, porque es lo que
 * la cajera acepta al abrir. Publicarlo aqui deja calcular el efectivo esperado al
 * centavo. El backend ya lo omite mientras hay una sesion abierta; esta pantalla es
 * la segunda linea, para que la fuga no vuelva a entrar por la otra puerta.
 */
function Historial({ sesiones }) {
  const [todas, setTodas] = useState(false)
  const cerradas = sesiones.filter((una) => una.estado === 'CERRADA')

  if (cerradas.length === 0) return null

  const visibles = todas ? cerradas : cerradas.slice(0, SESIONES_EN_LINEA)

  return (
    <div className="caja__seccion">
      <h2>Sesiones cerradas</h2>
      <div className="tabla-envoltura">
        <table className="tabla">
          <thead>
            <tr>
              <th>Sesión</th>
              <th>Cerrada</th>
              <th className="numero">Esperado</th>
              <th className="numero">Contado</th>
              <th>Diferencia</th>
              <th>Notas</th>
            </tr>
          </thead>
          <tbody>
            {visibles.map((una) => <FilaDeSesion key={una.id} sesion={una} />)}
          </tbody>
        </table>
      </div>

      {!todas && cerradas.length > SESIONES_EN_LINEA && (
        <Boton variante="plano" onClick={() => setTodas(true)}>
          Ver todas ({cerradas.length})
        </Boton>
      )}
    </div>
  )
}

function FilaDeSesion({ sesion }) {
  const diferencia = describirDiferencia(sesion.diferencia)

  return (
    <tr>
      <td>{sesion.consecutivo}</td>
      <td>{soloFechaYHora(sesion.fechaCierre)}</td>
      <td className="numero monto">{formatearPesos(sesion.efectivoEsperado)}</td>
      <td className="numero monto">{formatearPesos(sesion.efectivoContado)}</td>
      <td className={`arqueo__diferencia--${diferencia.tono}`}>
        {diferencia.texto}
        {diferencia.cuanto !== undefined && <> <span className="monto">{formatearPesos(diferencia.cuanto)}</span></>}
        {sinExplicar(sesion) && (
          <> <span className="insignia insignia--alerta">sin explicar</span></>
        )}
      </td>
      <td>
        {sesion.notas.length === 0
          ? <span className="texto-tenue">—</span>
          : sesion.notas.map((nota) => (
              <div key={nota.id}>
                {nota.texto}
                <span className="texto-tenue"> · {nota.usuario}</span>
              </div>
            ))}
      </td>
    </tr>
  )
}

// --------------------------------------------------------------------- fechas

// Las fechas llegan como TEXT ISO de ancho fijo: 'YYYY-MM-DDTHH:MM:SS'. Se cortan por
// posicion y no se parsean a Date, que en jsdom y en Windows interpreta la zona
// horaria de formas distintas y haria que la hora mostrada dependa del equipo.
const soloHora = (fecha) => (fecha ?? '').slice(11, 16)
const soloFecha = (fecha) => (fecha ?? '').slice(0, 10)
const soloFechaYHora = (fecha) => `${soloFecha(fecha)} ${soloHora(fecha)}`.trim()
