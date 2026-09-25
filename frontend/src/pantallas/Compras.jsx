import { useMemo, useState } from 'react'
import { PackageCheck, Plus } from 'lucide-react'

import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { filtrarCompras } from '../compras/useCompras.js'
import { PERMISOS, useSesion } from '../sesion/SesionContext.jsx'
import { etiqueta } from '../etiquetas.js'
import { AnularCompra, DescartarCompra } from './BajasDeCompra.jsx'
import { formatearPesos } from './Catalogo.jsx'
import { RecibirCompra } from './RecibirCompra.jsx'
import { RegistrarCompra } from './RegistrarCompra.jsx'

const ESTADOS = ['BORRADOR', 'RECIBIDA', 'DESCARTADA', 'ANULADA']

const INSIGNIA = {
  BORRADOR: 'insignia--alerta',
  RECIBIDA: 'insignia--exito',
  DESCARTADA: 'insignia--neutra',
  ANULADA: 'insignia--error',
}

/**
 * El listado de compras.
 *
 * LOS BORRADORES VAN PRIMERO Y SE CUENTAN. Un borrador es una factura registrada
 * cuya mercancia no ha entrado al inventario — o peor, mercancia que si llego a la
 * tienda y nadie termino de registrar. Es lo unico de esta lista que exige hacer
 * algo hoy; el resto es historia. Por eso el contador esta a la vista aunque no se
 * este filtrando por estado: un borrador olvidado no se nota mirando la pantalla.
 */
export function Compras({ catalogo, compras }) {
  const [vista, setVista] = useState({ tipo: 'listado' })
  const [baja, setBaja] = useState(null)
  const [estado, setEstado] = useState('')
  const [proveedorId, setProveedorId] = useState('')

  const visibles = useMemo(
    () => filtrarCompras(compras.filas, { estado, proveedorId }),
    [compras.filas, estado, proveedorId],
  )

  const volver = () => setVista({ tipo: 'listado' })

  if (vista.tipo === 'registrar') {
    return (
      <RegistrarCompra
        catalogo={catalogo}
        compras={compras}
        alCerrar={volver}
        alRecibir={(compra) => setVista({ tipo: 'recibir', compra })}
      />
    )
  }

  if (vista.tipo === 'recibir') {
    return (
      <RecibirCompra
        compra={vista.compra}
        catalogo={catalogo}
        compras={compras}
        alCerrar={volver}
      />
    )
  }

  if (compras.error) {
    return <AvisoDeError error={compras.error} alReintentar={compras.recargar} />
  }

  if (compras.cargando) {
    return <p className="texto-secundario">Cargando las compras…</p>
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Compras</h1>
        <div className="pantalla__acciones">
          <Boton
            variante="principal"
            icono={Plus}
            disabled={compras.sinProveedores}
            onClick={() => setVista({ tipo: 'registrar' })}
          >
            Registrar una compra
          </Boton>
        </div>
      </div>

      {compras.sinProveedores ? (
        <SinProveedores />
      ) : (
        <>
          {compras.borradores > 0 && (
            <Aviso tipo="alerta" titulo={`${compras.borradores} compra(s) en borrador`}>
              Todavía no han entrado al inventario. Si la mercancía ya llegó, hay que
              recibirlas para que el stock diga la verdad.
            </Aviso>
          )}

          <div className="filtros">
            <Campo etiqueta="Estado">
              {(props) => (
                <select {...props} value={estado} onChange={(e) => setEstado(e.target.value)}>
                  <option value="">Todos</option>
                  {ESTADOS.map((valor) => (
                    <option key={valor} value={valor}>{etiqueta(valor)}</option>
                  ))}
                </select>
              )}
            </Campo>

            <Campo etiqueta="Proveedor">
              {(props) => (
                <select {...props} value={proveedorId}
                        onChange={(e) => setProveedorId(e.target.value)}>
                  <option value="">Todos</option>
                  {compras.proveedores.map((p) => (
                    <option key={p.id} value={p.id}>{p.nombre}</option>
                  ))}
                </select>
              )}
            </Campo>
          </div>

          <p className="resumen-listado" role="status">
            {visibles.length} de {compras.filas.length} compras
          </p>

          {visibles.length === 0 ? (
            <Aviso tipo="info">Ninguna compra coincide con los filtros.</Aviso>
          ) : (
            <div className="tabla-envoltura">
              <table className="tabla">
                <thead>
                  <tr>
                    <th>Compra</th>
                    <th>Proveedor</th>
                    <th>Factura</th>
                    <th className="numero">Líneas</th>
                    <th className="numero">Total</th>
                    <th>Estado</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {visibles.map((compra) => (
                    <tr key={compra.id}>
                      <td className="monto">{compra.consecutivo}</td>
                      <td>{compra.proveedorNombre}</td>
                      <td>{compra.numeroFactura ?? '—'}</td>
                      <td className="numero monto">{compra.items.length}</td>
                      <td className="numero monto">{formatearPesos(compra.total)}</td>
                      <td>
                        <span className={`insignia ${INSIGNIA[compra.estado]}`}>
                          {etiqueta(compra.estado)}
                        </span>
                      </td>
                      <td className="fila__acciones">
                        <Acciones
                          compra={compra}
                          alRecibir={() => setVista({ tipo: 'recibir', compra })}
                          alDescartar={() => setBaja({ tipo: 'descartar', compra })}
                          alAnular={() => setBaja({ tipo: 'anular', compra })}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {baja?.tipo === 'descartar' && (
        <DescartarCompra
          compra={baja.compra}
          alCerrar={() => setBaja(null)}
          alHecho={async () => { await compras.recargar(); setBaja(null) }}
        />
      )}

      {baja?.tipo === 'anular' && (
        <AnularCompra
          compra={baja.compra}
          catalogo={catalogo}
          alCerrar={() => setBaja(null)}
          alHecho={async () => {
            await Promise.all([catalogo.recargar(), compras.recargar()])
            setBaja(null)
          }}
        />
      )}
    </>
  )
}

/**
 * Las acciones dependen del estado, y DESCARTAR Y ANULAR NO APARECEN JUNTOS NUNCA:
 * descartar solo existe en BORRADOR y anular solo en RECIBIDA. Tampoco se parecen —
 * anular lleva el boton de peligro— porque una no revierte nada y la otra mueve el
 * inventario.
 */
function Acciones({ compra, alRecibir, alDescartar, alAnular }) {
  // Recibir y anular tienen permisos propios, distintos del que abre esta pestaña.
  // Hoy los tres son de la DUENA y la diferencia no se nota, pero preguntarlo aqui
  // es lo que evita que el dia que se le conceda uno solo a alguien se le ofrezcan
  // los tres y dos le den 403.
  const { puede } = useSesion()

  if (compra.estado === 'BORRADOR') {
    const sinLineas = compra.items.length === 0
    return (
      <>
        {puede(PERMISOS.recibirCompras) && (
          <Boton variante="principal" onClick={alRecibir} disabled={sinLineas}>
            {sinLineas ? 'Sin líneas que recibir' : 'Recibir'}
          </Boton>
        )}
        <Boton variante="plano" onClick={alDescartar}>Descartar</Boton>
      </>
    )
  }

  if (compra.estado === 'RECIBIDA') {
    return puede(PERMISOS.anularCompras)
      ? <Boton variante="peligro" onClick={alAnular}>Anular</Boton>
      : null
  }

  return <span className="texto-secundario">{compra.motivoBaja}</span>
}

function SinProveedores() {
  return (
    <div className="estado-vacio">
      <PackageCheck size={40} className="estado-vacio__icono" aria-hidden="true" />
      <h2 className="estado-vacio__titulo">Primero hace falta un proveedor</h2>
      <p>
        Toda compra se registra a nombre de alguien. En la pestaña de Proveedores se crea
        el primero, y desde ahí ya se puede cargar una factura.
      </p>
    </div>
  )
}
