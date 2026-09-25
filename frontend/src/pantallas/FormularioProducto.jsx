import { useState } from 'react'

import { CODIGOS } from '../api/cliente.js'
import { catalogo as apiCatalogo } from '../api/endpoints.js'
import { AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'
import { SelectorConAlta } from '../componentes/SelectorConAlta.jsx'

/**
 * Alta y edicion de producto.
 *
 * Los errores se muestran donde corresponden, ramificando sobre `codigo` y no
 * sobre el texto: NOMBRE_DUPLICADO va debajo del nombre porque es el campo que hay
 * que cambiar, y VALIDACION_FALLIDA reparte sus `detalles` por campo. El mensaje
 * es el del backend, tal cual: ya esta escrito para leerse en pantalla y nombra el
 * registro con el que choca.
 */
export function FormularioProducto({ catalogo, producto, alCerrar, alGuardar }) {
  const [nombre, setNombre] = useState(producto?.nombre ?? '')
  const [marcaId, setMarcaId] = useState(producto?.marcaId ? String(producto.marcaId) : '')
  const [categoriaId, setCategoriaId] = useState(
    producto?.categoriaId ? String(producto.categoriaId) : '',
  )
  const [descripcion, setDescripcion] = useState(producto?.descripcion ?? '')
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const editando = Boolean(producto)
  const puedeGuardar = nombre.trim() && marcaId && categoriaId && !guardando

  // Una marca desactivada no se ofrece para un producto nuevo —desactivarla
  // significa exactamente eso—, pero si el producto que se edita ya la tiene sigue
  // en la lista: hacer desaparecer la seleccion actual convertiria un "guardar sin
  // tocar nada" en un cambio de marca silencioso.
  const marcas = catalogo.marcas.filter((m) => m.activo || String(m.id) === marcaId)
  const categorias = catalogo.categorias.filter((c) => c.activo || String(c.id) === categoriaId)

  async function guardar() {
    if (!puedeGuardar) return
    setGuardando(true)
    setError(null)
    const datos = {
      nombre: nombre.trim(),
      marcaId: Number(marcaId),
      categoriaId: Number(categoriaId),
      descripcion: descripcion.trim() || null,
    }
    try {
      // Se devuelve lo guardado porque quien encadena formularios lo necesita: al
      // registrar una compra, crear el producto es solo el primer paso y el
      // siguiente —la variante— tiene que abrir con este producto ya elegido.
      const guardado = editando
        ? await apiCatalogo.actualizarProducto(producto.id, datos)
        : await apiCatalogo.crearProducto(datos)
      await alGuardar(guardado)
    } catch (fallo) {
      setError(fallo)
    } finally {
      setGuardando(false)
    }
  }

  const errorDeNombre = error?.codigo === CODIGOS.nombreDuplicado
    ? error.message
    : detalleDe(error, 'nombre')

  return (
    <Modal
      titulo={editando ? 'Editar producto' : 'Nuevo producto'}
      alCerrar={alCerrar}
      amplio
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={guardando}>Cancelar</Boton>
          <Boton variante="principal" onClick={guardar} ocupado={guardando}
                 disabled={!puedeGuardar}>
            {editando ? 'Guardar' : 'Crear producto'}
          </Boton>
        </>
      }
    >
      <div className="formulario">
        {/* El orden de los campos es el orden del tabulador, y en el modal amplio
            tambien el de la rejilla: nombre, marca, categoria, descripcion. Es el
            orden en que se leen de la factura. */}
        <Campo
          etiqueta="Nombre del producto"
          value={nombre}
          error={errorDeNombre}
          autoFocus
          onChange={(e) => setNombre(e.target.value)}
        />

        <SelectorConAlta
          etiqueta="Marca"
          opciones={marcas}
          valor={marcaId}
          alCambiar={setMarcaId}
          alCrear={async (nuevo) => {
            const creada = await apiCatalogo.crearMarca(nuevo)
            await catalogo.recargar()
            return creada
          }}
          textoCrear="Nueva marca"
          error={detalleDe(error, 'marcaId')}
        />

        <SelectorConAlta
          etiqueta="Categoría"
          opciones={categorias}
          valor={categoriaId}
          alCambiar={setCategoriaId}
          alCrear={async (nuevo) => {
            const creada = await apiCatalogo.crearCategoria(nuevo)
            await catalogo.recargar()
            return creada
          }}
          textoCrear="Nueva categoría"
          error={detalleDe(error, 'categoriaId')}
        />

        <Campo
          etiqueta="Descripción (opcional)"
          value={descripcion}
          error={detalleDe(error, 'descripcion')}
          onChange={(e) => setDescripcion(e.target.value)}
        />

        {/* Lo que no cae en un campo concreto: fallo de red, 5xx, o un conflicto
            que no es de nombre. */}
        {error && !errorDeNombre && error.codigo !== CODIGOS.validacionFallida && (
          <AvisoDeError error={error} />
        )}
      </div>
    </Modal>
  )
}

/**
 * El detalle de validacion que corresponde a un campo. El backend los manda como
 * "campo: mensaje", asi que se reparten por prefijo en vez de mostrarlos todos
 * juntos en un monton donde nadie sabe cual es cual.
 */
export function detalleDe(error, campo) {
  if (error?.codigo !== CODIGOS.validacionFallida) return undefined
  const detalle = error.detalles.find((d) => d.startsWith(`${campo}:`))
  return detalle ? detalle.slice(campo.length + 1).trim() : undefined
}
