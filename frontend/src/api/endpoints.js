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

  /**
   * Anula y abre un borrador nuevo con las mismas lineas, en una sola llamada.
   * Devuelve {anulada, borrador}.
   */
  corregir: (id, motivo) => api.post(`/api/v1/compras/${id}/correccion`, { motivo }),
}

export const ventas = {
  /** Sin fecha, las de hoy. El servidor decide cual es hoy. */
  listar: (fecha) => api.get(fecha ? `/api/v1/ventas?fecha=${fecha}` : '/api/v1/ventas'),

  /**
   * Cobra. Devuelve {estado, datos}: el 201 dice que la venta se creo y el 200 que
   * el servidor devolvio una que ya existia con ese uuid.
   *
   * El cuerpo NO lleva total ni cambio. Los dos los calcula el servidor, y la
   * pantalla muestra despues los que devolvio.
   */
  cobrar: (datos) => api.postConEstado('/api/v1/ventas', datos),

  anular: (id, motivo) => api.post(`/api/v1/ventas/${id}/anulacion`, { motivo }),

  /**
   * Abre el recibo en el VISOR DE PDF DEL SISTEMA, no en el navegador.
   *
   * La aplicacion vive en una ventana en modo app, sin barra de direcciones: pedir el
   * PDF por HTTP abriria una ventana de navegador suelta encima de la pantalla de
   * cobro, con la clienta enfrente y alguien buscando como cerrarla. Por eso lo abre
   * el backend, que es el unico que puede hablar con el escritorio.
   */
  abrirRecibo: (id) => api.post(`/api/v1/ventas/${id}/recibo/apertura`),

  /** Para las ventas que quedaron sin comprobante. Si ya lo tiene, no reescribe nada. */
  generarRecibo: (id) => api.post(`/api/v1/ventas/${id}/recibo`),
}

/**
 * Quita el monto de un movimiento de caja. LA LINEA MAS IMPORTANTE DE ESTE ARCHIVO.
 *
 * Con la sesion ABIERTA, la suma de los movimientos ES el efectivo esperado: no hay
 * base inicial que la complete. El backend no lo publica —SesionDto.Abierta ni
 * siquiera tiene campo donde ponerlo— pero una lista que acumule los montos
 * reconstruye al centavo el numero que el cierre a ciegas existe para ocultar, y
 * quien cuenta sabiendo el resultado esperado cuenta hasta que le cuadre.
 *
 * Se descarta AQUI, en el limite, y no al pintar. Asi la pantalla no puede filtrarlo
 * aunque quiera, porque nunca lo ve. Si en cambio se dejara pasar y se omitiera en el
 * JSX, bastaria con que alguien agregue una columna "para verificar" —o un
 * console.log— y la fuga vuelve sin que nadie lo note mirando la pantalla.
 */
const sinMonto = ({ monto, ...resto }) => resto

export const caja = {
  /** Da 404 cuando no hay ninguna sesion abierta, que no es un error sino un estado. */
  sesionActual: () => api.get('/api/v1/caja/sesiones/actual'),

  /**
   * Abrir no lleva cuerpo: no hay base inicial ni nada que declarar. Si de anoche quedo
   * efectivo en el cajon, se declara despues con un movimiento INGRESO.
   */
  abrir: () => api.post('/api/v1/caja/sesiones'),

  /** El historial ya viene filtrado por permiso: la EMPLEADA solo recibe las suyas. */
  listar: () => api.get('/api/v1/caja/sesiones'),

  movimientos: async (sesionId) =>
    (await api.get(`/api/v1/caja/sesiones/${sesionId}/movimientos`)).map(sinMonto),

  /** La respuesta se devuelve SIN monto: lo que se envio no vuelve a la pantalla. */
  registrarMovimiento: async (datos) =>
    sinMonto(await api.post('/api/v1/caja/movimientos', datos)),

  /**
   * El conteo fisico y el cierre son la misma llamada, y por eso el cierre es
   * ciego: no hay un paso previo donde el sistema pueda mostrar el esperado. La
   * respuesta —{sesion, ventasPorMetodo}— es la primera y unica vez que aparecen
   * esperado, contado y diferencia.
   */
  cerrar: (sesionId, datos) => api.post(`/api/v1/caja/sesiones/${sesionId}/cierre`, datos),

  notas: (sesionId) => api.get(`/api/v1/caja/sesiones/${sesionId}/notas`),
  anotar: (sesionId, texto) => api.post(`/api/v1/caja/sesiones/${sesionId}/notas`, { texto }),
}

/**
 * Solo la DUENA: todas las respuestas llevan costos o margenes.
 *
 * El panel principal es UNA llamada. Vencimientos y sin rotacion son detalles que se
 * piden solo cuando alguien entra a mirarlos.
 */
export const metricas = {
  panel: (periodo, fecha) =>
    api.get(`/api/v1/metricas/panel?periodo=${periodo}&fecha=${fecha}`),
  vencimientos: () => api.get('/api/v1/metricas/vencimientos'),
  sinRotacion: (dias) => api.get(`/api/v1/metricas/sin-rotacion?dias=${dias}`),
}
