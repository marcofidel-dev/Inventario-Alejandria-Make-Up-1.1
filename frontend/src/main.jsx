import React from 'react'
import ReactDOM from 'react-dom/client'

import './estilos/fuentes.js'
import './estilos/tokens.css'
import './estilos/base.css'
import './componentes/componentes.css'
import './pantallas/pantallas.css'

import App from './App.jsx'
import { ProveedorDeSesion } from './sesion/SesionContext.jsx'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ProveedorDeSesion>
      <App />
    </ProveedorDeSesion>
  </React.StrictMode>,
)
