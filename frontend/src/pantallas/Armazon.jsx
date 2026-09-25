import { BarChart3, Boxes, LogOut, ShoppingCart, Truck, Wallet } from 'lucide-react'

import { Boton } from '../componentes/Boton.jsx'
import { etiqueta } from '../etiquetas.js'
import { PERMISOS, useSesion } from '../sesion/SesionContext.jsx'

/**
 * El armazon: encabezado de identidad, navegacion lateral con pestañas y area de
 * trabajo.
 *
 * El rosa vive aqui —encabezado y item activo— y el area de contenido se queda
 * neutra. Ocho horas de pantalla rosa saturado cansan la vista y tapan lo que
 * importa; ademas el rosa es el color de la accion, y si estuviera de fondo
 * dejaria de significar "esto se puede pulsar".
 *
 * LA NAVEGACION ESTA ORDENADA POR FRECUENCIA DE USO REAL, no por como se
 * construyo el sistema. Vender va a ser el 90% del uso y va primero. Carga inicial
 * se usa unos dias al principio de la vida del sistema y despues nunca, asi que no
 * es un modulo: es una pestaña de Inventario. Ajustes tampoco es cotidiano, es
 * excepcional, y esta al lado.
 *
 * INVENTARIO ES UNA SOLA SECCION Y ANTES ERAN DOS. Catalogo e Inventario acabaron
 * pareciendo dos puertas al mismo sitio: al quitarle al catalogo la creacion de
 * productos, esa capacidad se mudo a Carga inicial y a la compra, y lo que quedaba
 * de Catalogo —buscar, ver stock, editar precio— es exactamente lo que iba a
 * mostrar Existencias. Se fusionaron con el nombre que usa la dueña, y Existencias
 * desaparecio por duplicada.
 *
 * LO QUE TODAVIA NO EXISTE SE DECLARA IGUAL, deshabilitado y diciendolo. Asi la
 * estructura no hay que rediseñarla cuando lleguen el POS o las metricas, que es
 * cuando rediseñarla obligaria a reaprender donde esta todo.
 *
 * Los items se filtran por permiso y NO SE RENDERIZAN: para la EMPLEADA, Compras y
 * Metricas no existen en el DOM. Eso no protege nada —la autorizacion la impone el
 * interceptor del backend, que niega por defecto y responde 403— pero evita
 * ofrecerle una puerta que se le va a cerrar en la cara.
 */
const SECCIONES = [
  {
    id: 'vender',
    texto: 'Vender',
    icono: ShoppingCart,
    permiso: PERMISOS.registrarVentas,
    pestanas: [
      { id: 'cobrar', texto: 'Cobrar' },
      // El listado lo necesita quien vende: es donde encuentra la venta que hay que
      // anular. Anular es lo que pide permiso aparte, y eso se decide dentro.
      { id: 'ventas', texto: 'Ventas del día' },
    ],
  },
  {
    id: 'caja',
    texto: 'Caja',
    icono: Wallet,
    permiso: PERMISOS.operarCaja,
  },
  {
    id: 'inventario',
    texto: 'Inventario',
    icono: Boxes,
    pestanas: [
      { id: 'productos', texto: 'Productos' },
      { id: 'ajustes', texto: 'Ajustes', permiso: PERMISOS.ajustarInventario },
      { id: 'carga-inicial', texto: 'Carga inicial', permiso: PERMISOS.cargarInventarioInicial },
      { id: 'marcas', texto: 'Marcas y categorías', permiso: PERMISOS.editarCatalogo },
    ],
  },
  {
    id: 'compras',
    texto: 'Compras',
    icono: Truck,
    pestanas: [
      { id: 'compras', texto: 'Compras', permiso: PERMISOS.registrarCompras },
      { id: 'proveedores', texto: 'Proveedores', permiso: PERMISOS.gestionarProveedores },
    ],
  },
  {
    id: 'metricas',
    texto: 'Métricas',
    icono: BarChart3,
    permiso: PERMISOS.verMetricas,
    pestanas: [
      { id: 'panel', texto: 'Resumen' },
      { id: 'sin-rotacion', texto: 'Sin rotación' },
    ],
  },
]

/**
 * Las secciones que esta persona puede ver, con sus pestañas ya filtradas.
 *
 * Una seccion con pestañas se dibuja si al menos una de sus pestañas sobrevive al
 * filtro. De ahi sale solo el comportamiento que se quiere: a la EMPLEADA le
 * aparece Inventario con Productos nada mas, y Compras no le aparece porque
 * ninguna de sus dos pestañas es suya.
 */
export function seccionesVisibles(puede) {
  return SECCIONES
    .map((seccion) => {
      if (!seccion.pestanas) return seccion
      const pestanas = seccion.pestanas.filter((p) => !p.permiso || puede(p.permiso))
      return { ...seccion, pestanas }
    })
    .filter((seccion) => {
      if (seccion.permiso && !puede(seccion.permiso)) return false
      if (seccion.pestanas) return seccion.pestanas.length > 0
      return true
    })
}

/**
 * La pestaña con la que abre una seccion: la primera que de verdad tiene pantalla.
 * Abrir sobre una que solo dice "llega despues" seria dar la bienvenida con una
 * puerta cerrada.
 */
export function pestanaInicialDe(seccion) {
  if (!seccion?.pestanas?.length) return null
  const disponible = seccion.pestanas.find((p) => !p.proximamente)
  return (disponible ?? seccion.pestanas[0]).id
}

export function Armazon({ vista, alCambiarVista, children }) {
  const { usuario, puede, salir } = useSesion()
  const visibles = seccionesVisibles(puede)
  const seccionActual = visibles.find((seccion) => seccion.id === vista.seccion)

  return (
    <div className="armazon">
      <header className="encabezado">
        <span className="encabezado__marca">Alejandria MakeUp</span>
        <div className="encabezado__usuario">
          <span>{usuario.nombre}</span>
          <span className="insignia insignia--neutra">{etiqueta(usuario.rol)}</span>
          <Boton variante="plano" icono={LogOut} onClick={salir}>Salir</Boton>
        </div>
      </header>

      <nav className="navegacion" aria-label="Secciones">
        {visibles.map((seccion) => (
          <button
            key={seccion.id}
            type="button"
            className="navegacion__item"
            aria-current={vista.seccion === seccion.id ? 'page' : undefined}
            disabled={Boolean(seccion.proximamente)}
            title={seccion.proximamente || undefined}
            onClick={() => alCambiarVista({
              seccion: seccion.id,
              pestana: pestanaInicialDe(seccion),
            })}
          >
            <seccion.icono size={16} aria-hidden="true" />
            {seccion.texto}
            {seccion.proximamente && <span className="navegacion__pronto">pronto</span>}
          </button>
        ))}
      </nav>

      <main className="contenido">
        {seccionActual?.pestanas?.length > 1 && (
          <div className="pestanas" role="tablist" aria-label={seccionActual.texto}>
            {seccionActual.pestanas.map((pestana) => (
              <button
                key={pestana.id}
                type="button"
                role="tab"
                className="pestanas__item"
                aria-selected={vista.pestana === pestana.id}
                disabled={Boolean(pestana.proximamente)}
                title={pestana.proximamente || undefined}
                onClick={() => alCambiarVista({ seccion: seccionActual.id, pestana: pestana.id })}
              >
                {pestana.texto}
                {pestana.proximamente && <span className="navegacion__pronto">pronto</span>}
              </button>
            ))}
          </div>
        )}
        {children}
      </main>
    </div>
  )
}

/**
 * Lo que se ve donde todavia no hay pantalla. Dice que falta y por que aparece de
 * todos modos, en vez de dejar un area en blanco que parece un error.
 */
export function Proximamente({ titulo, children }) {
  return (
    <div className="estado-vacio">
      <h2 className="estado-vacio__titulo">{titulo}</h2>
      <p>{children}</p>
    </div>
  )
}
