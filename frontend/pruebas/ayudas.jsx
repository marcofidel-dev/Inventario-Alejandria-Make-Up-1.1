import { vi } from 'vitest'

/**
 * Datos con la forma exacta de CatalogoDto. Si el backend cambia la forma, estos
 * fixtures quedan mintiendo, asi que la forma se copia del record y no se
 * inventa.
 */
export function catalogoDePrueba(ajustes = {}) {
  return {
    marcas: [
      { id: 1, nombre: 'Loréal', activo: true },
      { id: 2, nombre: 'Maybelline', activo: true },
    ],
    categorias: [
      { id: 10, nombre: 'Labios', activo: true },
      { id: 11, nombre: 'Ojos', activo: true },
    ],
    productos: [
      { id: 100, nombre: 'Labial mate', marcaId: 1, categoriaId: 10, descripcion: null, activo: true },
      { id: 101, nombre: 'Máscara de pestañas', marcaId: 2, categoriaId: 11, descripcion: null, activo: true },
    ],
    variantes: [
      variante({ id: 1000, productoId: 100, tono: 'Rojo carmín', precioVenta: 32000, stock: 7 }),
      variante({ id: 1001, productoId: 100, tono: 'Nude', precioVenta: 32000, stock: 1, stockMinimo: 3 }),
      variante({ id: 1002, productoId: 101, tono: null, tamano: '9 ml', precioVenta: 45000, stock: 0 }),
    ],
    ...ajustes,
  }
}

export function variante(campos) {
  return {
    id: 1,
    productoId: 100,
    tono: null,
    tamano: null,
    codigoBarras: null,
    precioVenta: 10000,
    stockMinimo: 0,
    stock: 0,
    fechaVencimiento: null,
    paoMeses: null,
    activo: true,
    ...campos,
  }
}

export const CATALOGO_VACIO = { marcas: [], categorias: [], productos: [], variantes: [] }

/** Una sesion con los permisos que se le quieran dar, sin montar el proveedor real. */
export function sesionDe(rol, permisos) {
  return {
    usuario: { nombre: rol === 'DUENA' ? 'Alejandra' : 'Camila', rol, permisos },
    cargando: false,
    falloDeArranque: null,
    requiereConfiguracionInicial: false,
    puede: (permiso) => permisos.includes(permiso),
    entrar: () => {},
    salir: async () => {},
    reintentarArranque: async () => {},
  }
}

export const PERMISOS_DUENA = [
  'VER_COSTOS_Y_MARGENES', 'VER_METRICAS', 'EDITAR_CATALOGO', 'AJUSTAR_INVENTARIO',
  'CARGAR_INVENTARIO_INICIAL', 'GESTIONAR_USUARIOS', 'OPERAR_CAJA',
  'REGISTRAR_MOVIMIENTO_CAJA', 'RESPALDAR',
  'GESTIONAR_PROVEEDORES', 'REGISTRAR_COMPRAS', 'RECIBIR_COMPRAS', 'ANULAR_COMPRAS',
]

export const PERMISOS_EMPLEADA = ['OPERAR_CAJA', 'REGISTRAR_MOVIMIENTO_CAJA', 'RESPALDAR']

/** Proveedores con la forma de ProveedorDto. */
export function proveedoresDePrueba() {
  return [
    { id: 50, nombre: 'Distribuciones López', nit: '900123456-7', telefono: '3001234567', contacto: 'Marta', notas: null, activo: true },
    { id: 51, nombre: 'Cosméticos del Valle', nit: null, telefono: null, contacto: null, notas: null, activo: true },
  ]
}

/**
 * Compras con la forma de CompraDto. Los totales estan puestos a mano y no
 * calculados a partir de los items: el servidor es quien los calcula, y un fixture
 * que los derivara no podria distinguir el total del servidor del que sumaria la
 * pantalla.
 */
export function comprasDePrueba() {
  return [
    compra({ id: 900, consecutivo: 'C-000900', estado: 'RECIBIDA', total: 120000,
      fecha: '2026-08-10T09:00:00', fechaRecepcion: '2026-08-10T10:00:00' }),
    compra({ id: 901, consecutivo: 'C-000901', estado: 'BORRADOR', total: 60000,
      fecha: '2026-08-11T09:00:00' }),
    compra({ id: 902, consecutivo: 'C-000902', estado: 'ANULADA', total: 30000,
      fecha: '2026-08-12T09:00:00', motivoBaja: 'Mercancía equivocada',
      fechaBaja: '2026-08-12T11:00:00' }),
  ]
}

export function compra(campos) {
  return {
    id: 900,
    consecutivo: 'C-000900',
    proveedorId: 50,
    numeroFactura: 'F-1',
    total: 10000,
    estado: 'BORRADOR',
    fecha: '2026-08-10T09:00:00',
    fechaRecepcion: null,
    notas: null,
    fechaBaja: null,
    motivoBaja: null,
    items: [{ id: 1, varianteId: 1000, cantidad: 2, costoUnitario: 5000, subtotal: 10000 }],
    ...campos,
  }
}

/**
 * Un fetch de mentira que responde por ruta y registra todo lo que se le pidio.
 * Las pantallas ven el cliente HTTP real, incluida la traduccion de errores.
 */
export function fetchFalso(rutas) {
  return vi.fn(async (ruta, opciones) => {
    const entrada = Object.entries(rutas).find(([patron]) => ruta.startsWith(patron))
    if (!entrada) throw new Error(`ruta sin respuesta preparada: ${ruta}`)

    // Se espera el valor: una ruta puede devolver una promesa para dejar la
    // peticion en vuelo y comprobar lo que pasa mientras.
    const respuesta = await (typeof entrada[1] === 'function' ? entrada[1](ruta, opciones) : entrada[1])
    const { estado = 200, cuerpo = null } = respuesta ?? {}
    return {
      ok: estado >= 200 && estado < 300,
      status: estado,
      text: async () => (cuerpo === null ? '' : JSON.stringify(cuerpo)),
    }
  })
}
