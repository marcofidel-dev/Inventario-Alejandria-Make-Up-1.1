import { useMemo, useState } from 'react'
import { PackageOpen, Pencil, Plus } from 'lucide-react'

import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { filtrar } from '../catalogo/useCatalogo.js'
import { FormularioProducto } from './FormularioProducto.jsx'
import { FormularioVariante } from './FormularioVariante.jsx'
import { PERMISOS, useSesion } from '../sesion/SesionContext.jsx'

/**
 * El catalogo: la pantalla que la EMPLEADA mira todo el dia.
 *
 * La busqueda filtra en memoria sobre lo ya cargado. Ni una llamada por tecla: eso
 * funciona en desarrollo con tres productos y se cae en el mostrador con el
 * inventario real, justo cuando hay una clienta esperando.
 *
 * Sin costos en ninguna columna. No es que se oculten al pintar: el endpoint del
 * catalogo no los trae para nadie, y el de costos solo se llama con permiso.
 */
export function Catalogo({ catalogo }) {
  const { puede } = useSesion()
  const puedeEditar = puede(PERMISOS.editarCatalogo)

  const [texto, setTexto] = useState('')
  const [marcaId, setMarcaId] = useState('')
  const [categoriaId, setCategoriaId] = useState('')
  const [soloStockBajo, setSoloStockBajo] = useState(false)
  const [incluirInactivos, setIncluirInactivos] = useState(false)
  const [formulario, setFormulario] = useState(null)

  const visibles = useMemo(
    () => filtrar(catalogo.filas, { texto, marcaId, categoriaId, soloStockBajo, incluirInactivos }),
    [catalogo.filas, texto, marcaId, categoriaId, soloStockBajo, incluirInactivos],
  )

  if (catalogo.error) {
    return <AvisoDeError error={catalogo.error} alReintentar={catalogo.recargar} />
  }

  if (catalogo.cargando) {
    return <p className="texto-secundario">Cargando el catálogo…</p>
  }

  return (
    <>
      <div className="pantalla__cabecera">
        <h1>Catálogo</h1>
        {puedeEditar && (
          <div className="pantalla__acciones">
            <Boton icono={Plus} onClick={() => setFormulario({ tipo: 'producto' })}>
              Nuevo producto
            </Boton>
            <Boton
              variante="principal"
              icono={Plus}
              disabled={catalogo.productos.length === 0}
              onClick={() => setFormulario({ tipo: 'variante' })}
            >
              Nueva variante
            </Boton>
          </div>
        )}
      </div>

      {catalogo.estaVacio ? (
        <PrimerosPasos puedeEditar={puedeEditar} alCrearProducto={() => setFormulario({ tipo: 'producto' })} />
      ) : (
        <>
          <div className="filtros">
            <div className="filtros__buscador">
              <Campo etiqueta="Buscar">
                {(props) => (
                  <input
                    {...props}
                    value={texto}
                    placeholder="Marca, producto, tono o código de barras"
                    onChange={(e) => setTexto(e.target.value)}
                  />
                )}
              </Campo>
            </div>

            <Campo etiqueta="Marca">
              {(props) => (
                <select {...props} value={marcaId} onChange={(e) => setMarcaId(e.target.value)}>
                  <option value="">Todas</option>
                  {catalogo.marcas.map((m) => <option key={m.id} value={m.id}>{m.nombre}</option>)}
                </select>
              )}
            </Campo>

            <Campo etiqueta="Categoría">
              {(props) => (
                <select {...props} value={categoriaId}
                        onChange={(e) => setCategoriaId(e.target.value)}>
                  <option value="">Todas</option>
                  {catalogo.categorias.map((c) => (
                    <option key={c.id} value={c.id}>{c.nombre}</option>
                  ))}
                </select>
              )}
            </Campo>

            <label className="filtros__interruptor">
              <input type="checkbox" checked={soloStockBajo}
                     onChange={(e) => setSoloStockBajo(e.target.checked)} />
              Solo stock bajo
            </label>

            <label className="filtros__interruptor">
              <input type="checkbox" checked={incluirInactivos}
                     onChange={(e) => setIncluirInactivos(e.target.checked)} />
              Incluir inactivas
            </label>
          </div>

          <p className="resumen-listado" role="status">
            {visibles.length} de {catalogo.filas.length} variantes
          </p>

          {visibles.length === 0 ? (
            <Aviso tipo="info">
              Ninguna variante coincide con la búsqueda. Prueba con menos filtros.
            </Aviso>
          ) : (
            <div className="tabla-envoltura">
              <table className="tabla">
                <thead>
                  <tr>
                    <th>Marca</th>
                    <th>Producto</th>
                    <th>Tono</th>
                    <th>Tamaño</th>
                    <th className="numero">Precio</th>
                    <th className="numero">Stock</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {visibles.map((fila) => (
                    <tr key={fila.id} className={fila.activo ? undefined : 'inactiva'}>
                      <td>{fila.marcaNombre}</td>
                      <td>{fila.productoNombre}</td>
                      <td>{fila.tono ?? '—'}</td>
                      <td>{fila.tamano ?? '—'}</td>
                      <td className="numero monto">{formatearPesos(fila.precioVenta)}</td>
                      <td className="numero">
                        <span className="monto">{fila.stock}</span>
                        {/* Negativo manda sobre bajo: los dos serian ciertos a la
                            vez, pero solo uno de los dos es un descuadre. */}
                        {fila.stockNegativo ? (
                          <> <span className="insignia insignia--error">negativo</span></>
                        ) : fila.stockBajo && (
                          <> <span className="insignia insignia--alerta">bajo</span></>
                        )}
                      </td>
                      <td>
                        {puedeEditar && (
                          <Boton
                            variante="plano"
                            icono={Pencil}
                            aria-label={`Editar ${fila.productoNombre} ${fila.tono ?? ''}`}
                            onClick={() => setFormulario({ tipo: 'variante', variante: fila })}
                          />
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {formulario?.tipo === 'producto' && (
        <FormularioProducto
          catalogo={catalogo}
          alCerrar={() => setFormulario(null)}
          alGuardar={async () => { await catalogo.recargar(); setFormulario(null) }}
        />
      )}

      {formulario?.tipo === 'variante' && (
        <FormularioVariante
          catalogo={catalogo}
          variante={formulario.variante}
          alCerrar={() => setFormulario(null)}
          alGuardar={async () => { await catalogo.recargar(); setFormulario(null) }}
        />
      )}
    </>
  )
}

/**
 * Catalogo vacio: que hacer primero, no un mensaje triste. Quien abre el sistema
 * por primera vez no necesita que le digan que esta vacio — ya lo ve — necesita
 * saber por donde empezar.
 */
function PrimerosPasos({ puedeEditar, alCrearProducto }) {
  return (
    <div className="estado-vacio">
      <PackageOpen size={40} className="estado-vacio__icono" aria-hidden="true" />
      <h2 className="estado-vacio__titulo">Empecemos por el primer producto</h2>
      <p>
        Al crear un producto eliges su marca y su categoría, y si todavía no existen las
        creas ahí mismo. Después cada tono o tamaño es una variante.
      </p>
      {puedeEditar && (
        <div className="estado-vacio__acciones">
          <Boton variante="principal" icono={Plus} onClick={alCrearProducto}>
            Crear el primer producto
          </Boton>
        </div>
      )}
    </div>
  )
}

/** Pesos colombianos enteros, con separador de miles. Nunca decimales. */
export function formatearPesos(monto) {
  return new Intl.NumberFormat('es-CO').format(monto)
}
