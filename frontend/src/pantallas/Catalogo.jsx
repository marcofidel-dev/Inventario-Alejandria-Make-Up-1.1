import { useMemo, useState } from 'react'
import { PackageOpen, Pencil } from 'lucide-react'

import { Aviso, AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { filtrar } from '../catalogo/useCatalogo.js'
import { FormularioVariante } from './FormularioVariante.jsx'
import { PERMISOS, useSesion } from '../sesion/SesionContext.jsx'

/**
 * El catalogo: la pantalla que la EMPLEADA mira todo el dia.
 *
 * DE AQUI NO NACE NINGUN PRODUCTO. Los productos nacen donde entra mercancia fisica
 * con un costo —registrar una compra y la carga inicial— y esta pantalla administra
 * lo que ya existe: precio, stock minimo, activacion y correcciones de captura. Un
 * producto creado sin costo real se puede vender congelando costo 0 en la VentaItem,
 * y el margen historico queda corrompido para siempre sin que nadie lo note.
 *
 * Por lo mismo solo se listan las variantes CON HISTORIAL (`catalogo.filas`): una
 * variante creada dentro de un borrador de compra no aparece hasta que la compra se
 * recibe, y si el borrador se descarta no aparece nunca.
 *
 * La busqueda filtra en memoria sobre lo ya cargado. Ni una llamada por tecla: eso
 * funciona en desarrollo con tres productos y se cae en el mostrador con el
 * inventario real, justo cuando hay una clienta esperando.
 *
 * Sin costos en ninguna columna. No es que se oculten al pintar: el endpoint del
 * catalogo no los trae para nadie, y el de costos solo se llama con permiso.
 */
export function Catalogo({ catalogo, alIrA }) {
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
      </div>

      {catalogo.estaVacio ? (
        <PrimerosPasos alIrA={alIrA} />
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

      {/* Solo edicion: `variante` siempre viene puesta, aqui no se crea ninguna. */}
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
 * Catalogo vacio: por donde entran los productos, que no es por aqui.
 *
 * Ya no dice "crea tu primer producto" porque desde esta pantalla no se crea nada.
 * Un producto aparece cuando entra mercancia con un costo, y eso pasa en dos sitios.
 * Los enlaces llevan a los dos: decir de donde salen sin decir como llegar seria
 * dejar a alguien buscando en el menu.
 */
function PrimerosPasos({ alIrA }) {
  const { puede } = useSesion()

  return (
    <div className="estado-vacio">
      <PackageOpen size={40} className="estado-vacio__icono" aria-hidden="true" />
      <h2 className="estado-vacio__titulo">Todavía no hay productos con existencias</h2>
      <p>
        Los productos aparecen aquí cuando entra mercancía: al recibir una compra a un
        proveedor, o al hacer la carga inicial del inventario que ya está en la tienda.
        Desde el catálogo se administran los precios y las existencias, no se crean.
      </p>
      <div className="estado-vacio__acciones">
        {puede(PERMISOS.registrarCompras) && (
          <Boton variante="principal"
                 onClick={() => alIrA?.({ seccion: 'compras', pestana: 'compras' })}>
            Registrar una compra
          </Boton>
        )}
        {puede(PERMISOS.cargarInventarioInicial) && (
          <Boton onClick={() => alIrA?.({ seccion: 'inventario', pestana: 'carga-inicial' })}>
            Hacer la carga inicial
          </Boton>
        )}
      </div>
    </div>
  )
}

/** Pesos colombianos enteros, con separador de miles. Nunca decimales. */
export function formatearPesos(monto) {
  return new Intl.NumberFormat('es-CO').format(monto)
}
