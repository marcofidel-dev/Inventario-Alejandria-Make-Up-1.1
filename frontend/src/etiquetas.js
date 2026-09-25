/**
 * Los nombres legibles de los enums del backend.
 *
 * NINGUN IDENTIFICADOR DE CODIGO SE MUESTRA EN PANTALLA. `DUENA` se llama asi
 * porque es un identificador de Java y el valor de un CHECK en SQLite —cambiarlo
 * exige migracion y no vale la pena— pero en pantalla la persona se llama Dueña.
 * Un guion bajo visible es un bug, y lo mismo una palabra en mayusculas sostenidas
 * que nadie escribiria a mano.
 *
 * Un solo mapa para toda la aplicacion y no uno por pantalla: la misma
 * `VENTA_EFECTIVO` aparece en la caja y en el cierre, y dos mapas la traducirian
 * distinto el dia que alguien corrija uno solo.
 *
 * El valor sigue siendo el del backend en todo lo que no es texto — el front
 * ramifica sobre `estado === 'ANULADA'`, nunca sobre la etiqueta. Esto traduce al
 * pintar y nada mas.
 */
const ETIQUETAS = {
  // Rol (usuarios.Rol)
  DUENA: 'Dueña',
  EMPLEADA: 'Empleada',

  // Metodo de pago (ventas.MetodoPago)
  EFECTIVO: 'Efectivo',
  TARJETA: 'Tarjeta',
  NEQUI: 'Nequi',
  DAVIPLATA: 'Daviplata',
  TRANSFERENCIA: 'Transferencia',

  // Estado de venta (ventas.EstadoVenta)
  COMPLETADA: 'Completada',

  // Estado de compra (compras.EstadoCompra)
  BORRADOR: 'Borrador',
  RECIBIDA: 'Recibida',
  DESCARTADA: 'Descartada',
  ANULADA: 'Anulada',

  // Movimientos de caja (caja.TipoMovimientoCaja)
  VENTA_EFECTIVO: 'Venta en efectivo',
  RETIRO: 'Retiro',
  INGRESO: 'Ingreso',
  GASTO: 'Gasto',
  ANULACION: 'Anulación',

  // Movimientos de inventario (inventario.TipoMovimientoInventario)
  CARGA_INICIAL: 'Carga inicial',
  COMPRA: 'Compra',
  VENTA: 'Venta',
  AJUSTE: 'Ajuste',

  // Estado de sesion de caja (caja.EstadoSesionCaja)
  ABIERTA: 'Abierta',
  CERRADA: 'Cerrada',
}

/**
 * La etiqueta de un valor del backend.
 *
 * Lo que no esta en el mapa NO se muestra crudo: se humaniza — minusculas, el guion
 * bajo a espacio, la inicial en mayuscula. Un enum nuevo que nadie tradujo se ve
 * "Venta efectivo" en vez de "VENTA_EFECTIVO": mal escrito, pero no un identificador
 * en la cara de la clienta. La red de seguridad es la guarda de las pruebas, que
 * recorre lo renderizado buscando mayusculas con guion bajo.
 */
export function etiqueta(valor) {
  if (valor === null || valor === undefined || valor === '') return ''
  return ETIQUETAS[valor] ?? String(valor).toLowerCase().replace(/_/g, ' ')
    .replace(/^./, (inicial) => inicial.toUpperCase())
}

/** Solo para la prueba que exige que ningun enum de la interfaz quede sin traducir. */
export const VALORES_CON_ETIQUETA = Object.keys(ETIQUETAS)
