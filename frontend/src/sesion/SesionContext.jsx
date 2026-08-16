import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

import { alVencerLaSesion } from '../api/cliente.js'
import { autenticacion } from '../api/endpoints.js'

/**
 * Quien esta conectado y que puede hacer.
 *
 * Los permisos vienen en la respuesta del login, ya calculados por el backend
 * (PermisosPorRol), asi que el front no reimplementa la tabla de permisos: solo
 * pregunta. Reimplementarla seria garantizar que algun dia las dos versiones
 * discrepen.
 *
 * Ojo con lo que esto es y lo que no: `puede()` decide que se DIBUJA. La
 * autorizacion de verdad la impone el interceptor del backend, que niega por
 * defecto. Esconder un boton no protege nada; solo evita ofrecer algo que va a
 * fallar.
 */
const Contexto = createContext(null)

export const PERMISOS = {
  verCostos: 'VER_COSTOS_Y_MARGENES',
  editarCatalogo: 'EDITAR_CATALOGO',
  ajustarInventario: 'AJUSTAR_INVENTARIO',
  cargarInventarioInicial: 'CARGAR_INVENTARIO_INICIAL',
  gestionarUsuarios: 'GESTIONAR_USUARIOS',
  verMetricas: 'VER_METRICAS',
  operarCaja: 'OPERAR_CAJA',
  registrarVentas: 'REGISTRAR_VENTAS',
  anularVentas: 'ANULAR_VENTAS',
  registrarMovimientoCaja: 'REGISTRAR_MOVIMIENTO_CAJA',
  gestionarProveedores: 'GESTIONAR_PROVEEDORES',
  registrarCompras: 'REGISTRAR_COMPRAS',
  recibirCompras: 'RECIBIR_COMPRAS',
  anularCompras: 'ANULAR_COMPRAS',
}

export function ProveedorDeSesion({ children }) {
  const [usuario, setUsuario] = useState(null)
  const [requiereConfiguracionInicial, setRequiere] = useState(false)
  const [cargando, setCargando] = useState(true)
  const [falloDeArranque, setFalloDeArranque] = useState(null)

  const consultarEstado = useCallback(async () => {
    setCargando(true)
    setFalloDeArranque(null)
    try {
      const estado = await autenticacion.estado()
      setRequiere(estado.requiereConfiguracionInicial)
      if (estado.autenticado) {
        setUsuario(await autenticacion.sesion())
      } else {
        setUsuario(null)
      }
    } catch (error) {
      setFalloDeArranque(error)
    } finally {
      setCargando(false)
    }
  }, [])

  useEffect(() => {
    consultarEstado()
  }, [consultarEstado])

  // Si el backend responde 401 en cualquier llamada, la sesion se cayo: al
  // login, sin dejar la pantalla a medias esperando datos que no van a llegar.
  useEffect(() => alVencerLaSesion(() => setUsuario(null)), [])

  const valor = useMemo(
    () => ({
      usuario,
      cargando,
      falloDeArranque,
      requiereConfiguracionInicial,
      puede: (permiso) => Boolean(usuario?.permisos?.includes(permiso)),
      entrar: (usuarioEntrando) => {
        setUsuario(usuarioEntrando)
        setRequiere(false)
      },
      salir: async () => {
        try {
          await autenticacion.salir()
        } finally {
          setUsuario(null)
        }
      },
      reintentarArranque: consultarEstado,
    }),
    [usuario, cargando, falloDeArranque, requiereConfiguracionInicial, consultarEstado],
  )

  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>
}

export function useSesion() {
  const contexto = useContext(Contexto)
  if (!contexto) {
    throw new Error('useSesion tiene que usarse dentro de ProveedorDeSesion')
  }
  return contexto
}
