import { useState } from 'react'

import { AvisoDeError } from './componentes/Aviso.jsx'
import { Logo } from './componentes/Logo.jsx'
import { useCatalogo } from './catalogo/useCatalogo.js'
import { useCompras } from './compras/useCompras.js'
import { AjusteInventario } from './pantallas/AjusteInventario.jsx'
import { Armazon, Proximamente, seccionesVisibles } from './pantallas/Armazon.jsx'
import { Caja } from './pantallas/Caja.jsx'
import { CargaInicial } from './pantallas/CargaInicial.jsx'
import { Catalogo } from './pantallas/Catalogo.jsx'
import { Compras } from './pantallas/Compras.jsx'
import { ConfiguracionInicial } from './pantallas/ConfiguracionInicial.jsx'
import { Login } from './pantallas/Login.jsx'
import { MarcasYCategorias } from './pantallas/MarcasYCategorias.jsx'
import { Metricas, SinRotacion } from './pantallas/Metricas.jsx'
import { Proveedores } from './pantallas/Proveedores.jsx'
import { Venta } from './pantallas/Venta.jsx'
import { Ventas } from './pantallas/Ventas.jsx'
import { useSesion } from './sesion/SesionContext.jsx'

/**
 * El unico punto donde se decide que pantalla se ve.
 *
 * Sin router a proposito: cuatro vistas dentro de una ventana en modo app, sin
 * barra de direcciones ni boton de atras. Una variable de estado alcanza, y es
 * coherente con lo que este proyecto ya descarto por ser una app local — JWT,
 * CORS, paginacion. El dia que haga falta enlazar a una pantalla, se agrega.
 */
export default function App() {
  const { usuario, cargando, falloDeArranque, requiereConfiguracionInicial, entrar,
          reintentarArranque } = useSesion()

  if (cargando) {
    return <main className="login"><div className="login__caja">Abriendo…</div></main>
  }

  // Sin servidor no se puede ni saber si hay que configurar: es lo primero que
  // hay que decir, y con un boton para reintentar en vez de pedir que se
  // reinicie el programa.
  if (falloDeArranque) {
    return (
      <main className="login">
        <div className="login__caja">
          <h1 className="login__titulo"><Logo /></h1>
          <AvisoDeError error={falloDeArranque} alReintentar={reintentarArranque} />
        </div>
      </main>
    )
  }

  if (requiereConfiguracionInicial) {
    return <ConfiguracionInicial alConfigurar={entrar} />
  }

  if (!usuario) {
    return <Login alEntrar={entrar} />
  }

  return <Sesion />
}

/** La vista con la que abre la aplicacion: la lista de productos. */
const VISTA_INICIAL = { seccion: 'inventario', pestana: 'productos' }

function Sesion() {
  const { puede } = useSesion()
  const [vista, setVista] = useState(VISTA_INICIAL)
  const catalogo = useCatalogo()
  const compras = useCompras()

  // Si alguien queda parado en una vista que no le corresponde —por ejemplo tras
  // un cambio de usuario— se vuelve a la lista de productos, en vez de pintar una
  // pantalla que va a dar 403 en cada llamada. Se pregunta por la estructura ya filtrada
  // del armazon para no mantener aqui una segunda copia de los permisos.
  const visibles = seccionesVisibles(puede)
  const seccion = visibles.find((s) => s.id === vista.seccion)
  const pestana = seccion?.pestanas?.find((p) => p.id === vista.pestana)
  const vistaEfectiva = seccion && (!seccion.pestanas || pestana) ? vista : VISTA_INICIAL

  return (
    <Armazon vista={vistaEfectiva} alCambiarVista={setVista}>
      <Contenido vista={vistaEfectiva} catalogo={catalogo} compras={compras}
                 alIrA={setVista} />
    </Armazon>
  )
}

function Contenido({ vista, catalogo, compras, alIrA }) {
  const donde = `${vista.seccion}/${vista.pestana ?? ''}`

  switch (donde) {
    case 'vender/cobrar':
      // El punto de venta necesita mover la vista: sin caja abierta no deja armar el
      // carrito y lleva a la pantalla donde eso se arregla.
      return <Venta catalogo={catalogo} alIrA={alIrA} />
    case 'vender/ventas':
      return <Ventas />
    // Sin pestañas: pestanaInicialDe() devuelve null y la vista queda en 'caja/'.
    case 'caja/':
      return <Caja />
    case 'compras/compras':
      return <Compras catalogo={catalogo} compras={compras} />
    case 'compras/proveedores':
      return <Proveedores compras={compras} />
    case 'inventario/productos':
      // Inventario no crea productos: su estado vacio manda a las dos pantallas
      // por donde entra la mercancia, y para eso necesita mover la vista.
      return <Catalogo catalogo={catalogo} alIrA={alIrA} />
    case 'inventario/ajustes':
      return <AjusteInventario catalogo={catalogo} />
    case 'inventario/carga-inicial':
      return <CargaInicial catalogo={catalogo} />
    case 'inventario/marcas':
      return <MarcasYCategorias catalogo={catalogo} />
    case 'metricas/panel':
      return <Metricas />
    case 'metricas/sin-rotacion':
      return <SinRotacion />
    default:
      return (
        <Proximamente titulo="Esta pantalla todavía no existe">
          Aparece en la navegación para que la estructura no cambie cuando llegue, pero
          por ahora no hay nada que hacer aquí.
        </Proximamente>
      )
  }
}
