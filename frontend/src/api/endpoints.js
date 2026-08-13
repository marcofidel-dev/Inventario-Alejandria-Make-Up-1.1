import { api } from './cliente.js'

/**
 * Las llamadas del sistema, con nombre. Ninguna pantalla escribe una ruta a
 * mano: si el backend renombra algo, se arregla aqui y no en seis sitios.
 */
export const autenticacion = {
  estado: () => api.get('/api/v1/auth/estado'),
  perfiles: () => api.get('/api/v1/auth/perfiles'),
  configuracionInicial: (nombre, pin) =>
    api.post('/api/v1/auth/configuracion-inicial', { nombre, pin }),
  entrar: (nombre, pin) => api.post('/api/v1/auth/login', { nombre, pin }),
  salir: () => api.post('/api/v1/auth/logout'),
  sesion: () => api.get('/api/v1/auth/sesion'),
}

export const catalogo = {
  completo: () => api.get('/api/v1/catalogo'),

  /**
   * Costos y margenes. Solo se llama con el permiso VER_COSTOS_Y_MARGENES:
   * que el front no lo pida es la mitad de la regla, la otra la impone el
   * backend con un 403.
   */
  costos: () => api.get('/api/v1/catalogo/costos'),

  crearMarca: (nombre) => api.post('/api/v1/catalogo/marcas', { nombre }),
  renombrarMarca: (id, nombre) => api.put(`/api/v1/catalogo/marcas/${id}`, { nombre }),

  crearCategoria: (nombre) => api.post('/api/v1/catalogo/categorias', { nombre }),
  renombrarCategoria: (id, nombre) => api.put(`/api/v1/catalogo/categorias/${id}`, { nombre }),

  crearProducto: (datos) => api.post('/api/v1/catalogo/productos', datos),
  actualizarProducto: (id, datos) => api.put(`/api/v1/catalogo/productos/${id}`, datos),

  crearVariante: (datos) => api.post('/api/v1/catalogo/variantes', datos),
  actualizarVariante: (id, datos) => api.put(`/api/v1/catalogo/variantes/${id}`, datos),

  desactivar: (tipo, id) => api.post(`/api/v1/catalogo/${tipo}/${id}/desactivacion`),
  reactivar: (tipo, id) => api.post(`/api/v1/catalogo/${tipo}/${id}/reactivacion`),
}

export const inventario = {
  cargaInicial: (lineas) => api.post('/api/v1/inventario/carga-inicial', { lineas }),
  ajustar: (datos) => api.post('/api/v1/inventario/ajustes', datos),
}

export const proveedores = {
  listar: () => api.get('/api/v1/proveedores'),
  crear: (datos) => api.post('/api/v1/proveedores', datos),
  actualizar: (id, datos) => api.put(`/api/v1/proveedores/${id}`, datos),
  desactivar: (id) => api.post(`/api/v1/proveedores/${id}/desactivacion`),
  reactivar: (id) => api.post(`/api/v1/proveedores/${id}/reactivacion`),
}

export const compras = {
  listar: () => api.get('/api/v1/compras'),
  porId: (id) => api.get(`/api/v1/compras/${id}`),

  /**
   * El cuerpo NO lleva total: lo calcula el servidor sumando las lineas, y la
   * pantalla muestra despues el que devolvio.
   */
  crearBorrador: (datos) => api.post('/api/v1/compras', datos),
  actualizarBorrador: (id, datos) => api.put(`/api/v1/compras/${id}`, datos),

  descartar: (id, motivo) => api.post(`/api/v1/compras/${id}/descarte`, { motivo }),

  previaDeRecepcion: (id) => api.get(`/api/v1/compras/${id}/previa-recepcion`),
  previaDeAnulacion: (id) => api.get(`/api/v1/compras/${id}/previa-anulacion`),

  /**
   * Confirmar manda SOLO el id. Ningun numero de la previa vuelve al servidor:
   * el servidor recalcula con el stock del momento, porque entre que se abrio la
   * previa y se confirmo alguien pudo haber vendido.
   */
  recibir: (id) => api.post(`/api/v1/compras/${id}/recepcion`),
  anular: (id, motivo) => api.post(`/api/v1/compras/${id}/anulacion`, { motivo }),
}

export const caja = {
  /** Da 404 cuando no hay ninguna sesion abierta, que no es un error sino un estado. */
  sesionActual: () => api.get('/api/v1/caja/sesiones/actual'),
  registrarMovimiento: (datos) => api.post('/api/v1/caja/movimientos', datos),
}
