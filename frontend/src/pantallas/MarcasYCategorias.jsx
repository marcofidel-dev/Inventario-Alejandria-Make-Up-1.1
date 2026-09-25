import { useMemo, useState } from 'react'

import { catalogo as apiCatalogo } from '../api/endpoints.js'
import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'

/**
 * Marcas y categorias: administrarlas, no crearlas.
 *
 * AQUI NO NACE NINGUNA. Una marca o una categoria se crea desde el desplegable, en
 * el momento en que hace falta para un producto que esta entrando —una compra o la
 * carga inicial—, y por la misma razon por la que el producto tampoco se crea aqui:
 * la que se crea "por si acaso" nadie la usa y nadie la borra.
 *
 * Lo que se puede hacer es lo que se necesita cuando ya existen: renombrar la que
 * quedo mal escrita, desactivar la que ya no se trabaja, y ver cuantos productos
 * tiene cada una — que es lo que dice si desactivarla es inofensivo o si va a dejar
 * huerfana media vitrina.
 *
 * DESACTIVAR NO ESCONDE NADA DE LO YA REGISTRADO: los productos siguen en la lista
 * y se siguen vendiendo. Lo unico que cambia es que deja de ofrecerse al crear
 * productos nuevos, que es justo lo que quiere decir "esta marca ya no la trabajo".
 *
 * FUSIONAR DUPLICADAS NO ESTA, y no es un olvido: es una decision pendiente. El
 * indice normalizado ya atrapa "Loréal" contra "LOREAL"; lo que ningun indice puede
 * atrapar es "Loreal" contra "L'Oréal Paris". Fusionar es reasignar los productos de
 * una a otra y desactivar la que queda vacia — trabajo real que solo vale la pena si
 * el caso se da. Mientras tanto, renombrar y desactivar cubren lo que se sabe que
 * pasa.
 */
export function MarcasYCategorias({ catalogo }) {
  const [renombrando, setRenombrando] = useState(null)
  const [error, setError] = useState(null)

  // Los productos que cuelgan de cada marca y de cada categoria. Se cuentan sobre
  // `catalogo.productos`, que ya esta cargado: no hace falta pedirselo al servidor.
  const conteos = useMemo(() => {
    const porMarca = new Map()
    const porCategoria = new Map()
    for (const producto of catalogo.productos) {
      porMarca.set(producto.marcaId, (porMarca.get(producto.marcaId) ?? 0) + 1)
      porCategoria.set(producto.categoriaId, (porCategoria.get(producto.categoriaId) ?? 0) + 1)
    }
    return { porMarca, porCategoria }
  }, [catalogo.productos])

  async function cambiarActivo(tipo, elemento, activo) {
    setError(null)
    try {
      await (activo ? apiCatalogo.reactivar(tipo, elemento.id)
        : apiCatalogo.desactivar(tipo, elemento.id))
      await catalogo.recargar()
    } catch (fallo) {
      setError(fallo)
    }
  }

  if (catalogo.error) {
    return <AvisoDeError error={catalogo.error} alReintentar={catalogo.recargar} />
  }

  if (catalogo.cargando) {
    return <p className="texto-secundario">Cargando el catálogo…</p>
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Marcas y categorías</h1>
      </div>

      <Aviso tipo="info">
        Las marcas y las categorías se crean al registrar una compra o en la carga
        inicial, cuando de verdad hacen falta. Aquí se corrigen, se desactivan y se
        vuelven a activar.
      </Aviso>

      {error && <AvisoDeError error={error} />}

      <div className="listas-pareja">
        <Lista
          titulo="Marcas"
          tipo="marcas"
          elementos={catalogo.marcas}
          conteos={conteos.porMarca}
          alRenombrar={(marca) => setRenombrando({ tipo: 'marcas', elemento: marca })}
          alCambiarActivo={cambiarActivo}
        />
        <Lista
          titulo="Categorías"
          tipo="categorias"
          elementos={catalogo.categorias}
          conteos={conteos.porCategoria}
          alRenombrar={(categoria) => setRenombrando({ tipo: 'categorias', elemento: categoria })}
          alCambiarActivo={cambiarActivo}
        />
      </div>

      {renombrando && (
        <Renombrar
          tipo={renombrando.tipo}
          elemento={renombrando.elemento}
          alCerrar={() => setRenombrando(null)}
          alGuardar={async () => { await catalogo.recargar(); setRenombrando(null) }}
        />
      )}
    </>
  )
}

/**
 * Una de las dos listas. Son la misma tabla con otro nombre, asi que es un solo
 * componente: dos copias divergen a la primera correccion que alguien hace en una
 * sola de ellas.
 */
function Lista({ titulo, tipo, elementos, conteos, alRenombrar, alCambiarActivo }) {
  if (elementos.length === 0) {
    return (
      <section>
        <h2 className="seccion__titulo">{titulo}</h2>
        <div className="estado-vacio">
          <p>Todavía no hay ninguna. Aparecen al registrar la primera compra.</p>
        </div>
      </section>
    )
  }

  return (
    <section>
      <h2 className="seccion__titulo">{titulo}</h2>
      <div className="tabla-envoltura">
        <table className="tabla">
          <thead>
            <tr>
              <th>Nombre</th>
              <th className="numero">Productos</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {elementos.map((elemento) => {
              const cuantos = conteos.get(elemento.id) ?? 0
              return (
                <tr key={elemento.id} className={elemento.activo ? undefined : 'inactiva'}>
                  <td>
                    {elemento.nombre}
                    {!elemento.activo && (
                      <> <span className="insignia insignia--neutra">desactivada</span></>
                    )}
                  </td>
                  <td className="numero">
                    <span className="monto">{cuantos}</span>
                    {/* Cero productos no es un error, pero es lo que hay que ver para
                        saber cual se puede desactivar sin pensarlo dos veces. */}
                    {cuantos === 0 && (
                      <> <span className="insignia insignia--neutra">sin productos</span></>
                    )}
                  </td>
                  <td className="fila__acciones">
                    <Boton variante="plano" onClick={() => alRenombrar(elemento)}>
                      Renombrar
                    </Boton>
                    <Boton
                      variante="plano"
                      onClick={() => alCambiarActivo(tipo, elemento, !elemento.activo)}
                    >
                      {elemento.activo ? 'Desactivar' : 'Reactivar'}
                    </Boton>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/**
 * Renombrar. El mensaje del 409 se muestra tal cual porque nombra la marca con la
 * que se choca: "Ya existe la marca «Loréal»…" es exactamente lo que hace falta
 * saber para decidir si hay que renombrar o si lo que sobra es la otra.
 */
function Renombrar({ tipo, elemento, alCerrar, alGuardar }) {
  const [nombre, setNombre] = useState(elemento.nombre)
  const [guardando, setGuardando] = useState(false)
  const [error, setError] = useState(null)

  const esMarca = tipo === 'marcas'
  const puedeGuardar = nombre.trim() && nombre.trim() !== elemento.nombre && !guardando

  async function guardar() {
    if (!puedeGuardar) return
    setGuardando(true)
    setError(null)
    try {
      await (esMarca ? apiCatalogo.renombrarMarca(elemento.id, nombre.trim())
        : apiCatalogo.renombrarCategoria(elemento.id, nombre.trim()))
      await alGuardar()
    } catch (fallo) {
      setError(fallo.message)
    } finally {
      setGuardando(false)
    }
  }

  return (
    <Modal
      titulo={esMarca ? 'Renombrar la marca' : 'Renombrar la categoría'}
      alCerrar={alCerrar}
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={guardando}>Cancelar</Boton>
          <Boton variante="principal" onClick={guardar} ocupado={guardando}
                 disabled={!puedeGuardar}>
            Guardar
          </Boton>
        </>
      }
    >
      <div className="formulario">
        <Campo
          etiqueta="Nombre"
          value={nombre}
          error={error}
          autoFocus
          onChange={(e) => setNombre(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); guardar() } }}
        />
        <p className="texto-secundario">
          El nombre cambia en todos los productos que ya la usan: es la misma marca,
          escrita bien.
        </p>
      </div>
    </Modal>
  )
}
