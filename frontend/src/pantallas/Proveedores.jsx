import { useState } from 'react'
import { Pencil, Plus, Truck } from 'lucide-react'

import { proveedores as apiProveedores } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { FormularioProveedor } from './FormularioProveedor.jsx'

/**
 * Proveedores: quien nos vende.
 *
 * Se toca cada varios meses, no todos los dias, y por eso vive como pestaña de
 * Compras y no como seccion propia. Nadie entra aqui a hacer algo: entra a
 * registrar una compra y crea el proveedor si falta.
 *
 * No se borra ninguno, se desactiva. Un proveedor borrado se llevaria consigo el
 * historial de a quien se le compro que, que es justo lo que alguien va a querer
 * mirar el dia que un lote salga malo.
 */
export function Proveedores({ compras }) {
  const [formulario, setFormulario] = useState(null)
  const [cambiando, setCambiando] = useState(null)
  const [error, setError] = useState(null)

  async function cambiarActivo(proveedor) {
    if (cambiando) return
    setCambiando(proveedor.id)
    setError(null)
    try {
      if (proveedor.activo) await apiProveedores.desactivar(proveedor.id)
      else await apiProveedores.reactivar(proveedor.id)
      await compras.recargar()
    } catch (fallo) {
      setError(fallo)
    } finally {
      setCambiando(null)
    }
  }

  if (compras.error) {
    return <AvisoDeError error={compras.error} alReintentar={compras.recargar} />
  }

  if (compras.cargando) {
    return <p className="texto-secundario">Cargando los proveedores…</p>
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Proveedores</h1>
        <div className="pantalla__acciones">
          <Boton variante="principal" icono={Plus} onClick={() => setFormulario({})}>
            Nuevo proveedor
          </Boton>
        </div>
      </div>

      {error && <AvisoDeError error={error} />}

      {compras.sinProveedores ? (
        <PrimerProveedor alCrear={() => setFormulario({})} />
      ) : (
        <div className="tabla-envoltura">
          <table className="tabla">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>NIT</th>
                <th>Teléfono</th>
                <th>Contacto</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {compras.proveedores.map((proveedor) => (
                <tr key={proveedor.id} className={proveedor.activo ? undefined : 'inactiva'}>
                  <td>
                    {proveedor.nombre}
                    {!proveedor.activo && (
                      <> <span className="insignia insignia--neutra">inactivo</span></>
                    )}
                  </td>
                  <td>{proveedor.nit ?? '—'}</td>
                  <td>{proveedor.telefono ?? '—'}</td>
                  <td>{proveedor.contacto ?? '—'}</td>
                  <td className="fila__acciones">
                    <Boton
                      variante="plano"
                      icono={Pencil}
                      aria-label={`Editar ${proveedor.nombre}`}
                      onClick={() => setFormulario({ proveedor })}
                    />
                    <Boton
                      variante="plano"
                      ocupado={cambiando === proveedor.id}
                      textoOcupado="…"
                      onClick={() => cambiarActivo(proveedor)}
                    >
                      {proveedor.activo ? 'Desactivar' : 'Reactivar'}
                    </Boton>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {formulario && (
        <FormularioProveedor
          proveedor={formulario.proveedor}
          alCerrar={() => setFormulario(null)}
          alGuardar={async () => { await compras.recargar(); setFormulario(null) }}
        />
      )}
    </>
  )
}

/**
 * Sin proveedores, lo primero es crear uno — no un mensaje triste. Que la lista
 * este vacia ya se ve; lo que hace falta es saber por donde se empieza y por que
 * hay que empezar por ahi.
 */
function PrimerProveedor({ alCrear }) {
  return (
    <div className="estado-vacio">
      <Truck size={40} className="estado-vacio__icono" aria-hidden="true" />
      <h2 className="estado-vacio__titulo">Empecemos por el primer proveedor</h2>
      <p>
        Toda compra se registra a nombre de alguien, así que este es el primer paso para
        poder cargar una factura. Basta con el nombre; el NIT y el teléfono se pueden
        agregar después.
      </p>
      <div className="estado-vacio__acciones">
        <Boton variante="principal" icono={Plus} onClick={alCrear}>
          Crear el primer proveedor
        </Boton>
      </div>
    </div>
  )
}
