import { useState } from 'react'

import { AvisoDeError } from './componentes/Aviso.jsx'
import { useCatalogo } from './catalogo/useCatalogo.js'
import { useCompras } from './compras/useCompras.js'
import { AjusteInventario } from './pantallas/AjusteInventario.jsx'
import { Armazon, Proximamente, seccionesVisibles } from './pantallas/Armazon.jsx'
import { CargaInicial } from './pantallas/CargaInicial.jsx'
import { Catalogo } from './pantallas/Catalogo.jsx'
import { Compras } from './pantallas/Compras.jsx'
import { ConfiguracionInicial } from './pantallas/ConfiguracionInicial.jsx'
import { Login } from './pantallas/Login.jsx'
import { Proveedores } from './pantallas/Proveedores.jsx'
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
          <h1 className="login__titulo">Alejandria MakeUp</h1>
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
const VISTA_INICIAL = { seccion: 'catalogo', pestana: 'productos' }

function Sesion() {
  const { puede } = useSesion()
  const [vista, setVista] = useState(VISTA_INICIAL)
  const catalogo = useCatalogo()
  const compras = useCompras()

  // Si alguien queda parado en una vista que no le corresponde —por ejemplo tras
  // un cambio de usuario— se vuelve al catalogo, en vez de pintar una pantalla
  // que va a dar 403 en cada llamada. Se pregunta por la estructura ya filtrada
  // del armazon para no mantener aqui una segunda copia de los permisos.
  const visibles = seccionesVisibles(puede)
  const seccion = visibles.find((s) => s.id === vista.seccion)
  const pestana = seccion?.pestanas?.find((p) => p.id === vista.pestana)
  const vistaEfectiva = seccion && (!seccion.pestanas || pestana) ? vista : VISTA_INICIAL

  return (
    <Armazon vista={vistaEfectiva} alCambiarVista={setVista}>
      <Contenido vista={vistaEfectiva} catalogo={catalogo} compras={compras} />
    </Armazon>
  )
}

function Contenido({ vista, catalogo, compras }) {
  const donde = `${vista.seccion}/${vista.pestana ?? ''}`

  switch (donde) {
    case 'catalogo/productos':
      return <Catalogo catalogo={catalogo} />
    case 'compras/compras':
      return <Compras catalogo={catalogo} compras={compras} />
    case 'compras/proveedores':
      return <Proveedores compras={compras} />
    case 'inventario/ajustes':
      return <AjusteInventario catalogo={catalogo} />
    case 'inventario/carga-inicial':
      return <CargaInicial catalogo={catalogo} />
    default:
      return (
        <Proximamente titulo="Esta pantalla todavía no existe">
          Aparece en la navegación para que la estructura no cambie cuando llegue, pero
          por ahora no hay nada que hacer aquí.
        </Proximamente>
      )
  }
}
