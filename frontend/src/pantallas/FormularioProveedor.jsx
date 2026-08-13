import { useState } from 'react'

import { CODIGOS } from '../api/cliente.js'
import { proveedores as apiProveedores } from '../api/endpoints.js'
import { AvisoDeError } from '../componentes/Aviso.jsx'
import { Boton } from '../componentes/Boton.jsx'
import { Campo } from '../componentes/Campo.jsx'
import { Modal } from '../componentes/Modal.jsx'
import { detalleDe } from './FormularioProducto.jsx'

/**
 * Alta y edicion de proveedor.
 *
 * Mismo trato de errores que el formulario de producto: se ramifica sobre `codigo`
 * y no sobre el texto. NOMBRE_DUPLICADO va debajo del nombre porque es el campo
 * que hay que cambiar, y el mensaje es el del backend tal cual — ya nombra al
 * proveedor con el que se choca, que es el dato util.
 */
export function FormularioProveedor({ proveedor, alCerrar, alGuardar }) {
  const [nombre, setNombre] = useState(proveedor?.nombre ?? '')
  const [nit, setNit] = useState(proveedor?.nit ?? '')
  const [telefono, setTelefono] = useState(proveedor?.telefono ?? '')
  const [contacto, setContacto] = useState(proveedor?.contacto ?? '')
  const [notas, setNotas] = useState(proveedor?.notas ?? '')
  const [error, setError] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const editando = Boolean(proveedor)
  const puedeGuardar = nombre.trim() && !guardando

  async function guardar() {
    if (!puedeGuardar) return
    setGuardando(true)
    setError(null)
    const datos = {
      nombre: nombre.trim(),
      nit: nit.trim() || null,
      telefono: telefono.trim() || null,
      contacto: contacto.trim() || null,
      notas: notas.trim() || null,
    }
    try {
      const guardado = editando
        ? await apiProveedores.actualizar(proveedor.id, datos)
        : await apiProveedores.crear(datos)
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
      titulo={editando ? 'Editar proveedor' : 'Nuevo proveedor'}
      alCerrar={alCerrar}
      acciones={
        <>
          <Boton variante="plano" onClick={alCerrar} disabled={guardando}>Cancelar</Boton>
          <Boton variante="principal" onClick={guardar} ocupado={guardando}
                 disabled={!puedeGuardar}>
            {editando ? 'Guardar' : 'Crear proveedor'}
          </Boton>
        </>
      }
    >
      <div className="formulario">
        <Campo
          etiqueta="Nombre"
          value={nombre}
          error={errorDeNombre}
          autoFocus
          onChange={(e) => setNombre(e.target.value)}
        />

        <div className="formulario__pareja">
          <Campo etiqueta="NIT (opcional)" value={nit} error={detalleDe(error, 'nit')}
                 onChange={(e) => setNit(e.target.value)} />
          <Campo etiqueta="Teléfono (opcional)" value={telefono}
                 error={detalleDe(error, 'telefono')}
                 onChange={(e) => setTelefono(e.target.value)} />
        </div>

        <Campo etiqueta="Contacto (opcional)" value={contacto}
               error={detalleDe(error, 'contacto')}
               onChange={(e) => setContacto(e.target.value)} />

        <Campo etiqueta="Notas (opcional)" value={notas} error={detalleDe(error, 'notas')}
               ayuda="Por ejemplo, qué días despacha."
               onChange={(e) => setNotas(e.target.value)} />

        {error && !errorDeNombre && error.codigo !== CODIGOS.validacionFallida && (
          <AvisoDeError error={error} />
        )}
      </div>
    </Modal>
  )
}
