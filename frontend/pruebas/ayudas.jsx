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
      // `descripcion` va escrita a mano y con el separador del backend, no derivada de
      // los otros campos: derivarla seria reimplementar Descripcion.de() dentro del
      // fixture, y entonces las pruebas no podrian notar que el front y el backend
      // dejaron de decir lo mismo — que es exactamente lo que paso.
      variante({ id: 1000, productoId: 100, tono: 'Rojo carmín', precioVenta: 32000, stock: 7,
        descripcion: 'Loréal|Labial mate|Rojo carmín' }),
      variante({ id: 1001, productoId: 100, tono: 'Nude', precioVenta: 32000, stock: 1, stockMinimo: 3,
        descripcion: 'Loréal|Labial mate|Nude' }),
      variante({ id: 1002, productoId: 101, tono: null, tamano: '9 ml', precioVenta: 45000, stock: 0,
        descripcion: 'Maybelline|Máscara de pestañas|9 ml' }),
      // Sin historial: creada dentro de un borrador de compra que todavia no llega.
      // Va en el fixture compartido a proposito — el backend SI la manda, y una
      // prueba que afirme que no se lista no demuestra nada si nunca estuvo.
      variante({ id: 1003, productoId: 100, tono: 'Coral pendiente', precioVenta: 32000,
        stock: 0, conHistorial: false, descripcion: 'Loréal|Labial mate|Coral pendiente' }),
    ],
    ...ajustes,
  }
}

export function variante(campos) {
  return {
    id: 1,
    productoId: 100,
    // La arma el backend con Descripcion.de(): marca, producto, tono y tamaño unidos
    // por la barra pegada. El front la muestra tal cual y no la reconstruye.
    descripcion: 'Loréal|Labial mate',
    tono: null,
    tamano: null,
    codigoBarras: null,
    precioVenta: 10000,
    stockMinimo: 0,
    stock: 0,
    conHistorial: true,
    // La bandera del catalogo, no un costo. `true` significa costo promedio en cero:
    // nunca entro mercancia valorada, o todas las compras que la valoraban se anularon.
    // Es lo que el punto de venta mira para rechazar la linea al agregarla.
    sinCosto: false,
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

/** La DUENA puede todo: el backend le da el enum completo. */
export const PERMISOS_DUENA = [
  'VER_COSTOS_Y_MARGENES', 'VER_METRICAS', 'EDITAR_CATALOGO', 'AJUSTAR_INVENTARIO',
  'CARGAR_INVENTARIO_INICIAL', 'GESTIONAR_USUARIOS', 'OPERAR_CAJA',
  'REGISTRAR_MOVIMIENTO_CAJA', 'VER_SESIONES_DE_OTROS', 'RESPALDAR',
  'REGISTRAR_VENTAS', 'ANULAR_VENTAS',
  'GESTIONAR_PROVEEDORES', 'REGISTRAR_COMPRAS', 'RECIBIR_COMPRAS', 'ANULAR_COMPRAS',
]

/**
 * Lo que la EMPLEADA si puede, copiado de PermisosPorRol.DE_EMPLEADA. Vender es su
 * trabajo; anular no, porque devuelve inventario y saca plata del cajon del dia.
 */
export const PERMISOS_EMPLEADA = [
  'OPERAR_CAJA', 'REGISTRAR_MOVIMIENTO_CAJA', 'REGISTRAR_VENTAS', 'RESPALDAR',
]

/**
 * Una venta con la forma de VentaDto, tal como sale del cobro.
 *
 * `cambio` va aparte y explicito: el que vale es el del servidor, y para poder probar
 * que la pantalla usa ese y no el que venia calculando hay que poder mandarlos
 * distintos.
 */
export function ventaDePrueba(campos = {}) {
  return {
    id: 500,
    uuid: 'el-que-mando-la-pantalla',
    consecutivo: 'V-000123',
    fecha: '2026-08-15T14:32:00',
    sesionCajaId: 42,
    usuario: 'Camila',
    subtotal: 32000,
    descuento: 0,
    total: 32000,
    metodoPago: 'EFECTIVO',
    efectivoRecibido: 50000,
    cambio: 18000,
    estado: 'COMPLETADA',
    fechaAnulacion: null,
    motivoAnulacion: null,
    rutaRecibo: null,
    lineas: [{
      varianteId: 1000,
      descripcion: 'Loréal Labial mate Rojo carmín',
      cantidad: 1,
      precioUnitario: 32000,
      descuentoProrrateado: 0,
      subtotal: 32000,
    }],
    variantesEnNegativo: [],
    ...campos,
  }
}

/**
 * Una fila del listado, con la forma de VentaDto.Resumen: sin lineas y sin costos.
 *
 * `rutaRecibo` viaja aqui porque el record del backend la trae, y es lo que decide
 * que ofrece cada fila: "Ver recibo" si hay archivo, "Generar recibo" si esta en
 * nulo. Un fixture sin ella dejaria a las pruebas afirmando sobre una API que no es.
 */
export function resumenDeVenta(campos = {}) {
  return {
    id: 500,
    consecutivo: 'V-000123',
    fecha: '2026-08-15T14:32:00',
    total: 32000,
    metodoPago: 'EFECTIVO',
    estado: 'COMPLETADA',
    usuario: 'Camila',
    motivoAnulacion: null,
    rutaRecibo: 'recibos/2026/08/V-000123.pdf',
    ...campos,
  }
}

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
 * Una sesion abierta con la forma de SesionDto.Abierta.
 *
 * NO TIENE baseInicial NI efectivoEsperado, y no es que vayan en null: el record del
 * backend no tiene esos campos. El fixture copia esa forma exacta porque si los
 * inventara, las pruebas estarian afirmando cosas sobre una API que no existe.
 */
export function sesionAbierta(ajustes = {}) {
  return {
    id: 42,
    consecutivo: 'C-000042',
    estado: 'ABIERTA',
    fechaApertura: '2026-08-13T08:12:00',
    usuarioApertura: 'Camila',
    cantidadDeMovimientos: 0,
    esDeUnDiaAnterior: false,
    ...ajustes,
  }
}

/** Una sesion cerrada con la forma de SesionDto.Cerrada. */
export function sesionCerrada(ajustes = {}) {
  return {
    id: 41,
    consecutivo: 'C-000041',
    estado: 'CERRADA',
    fechaApertura: '2026-08-12T08:00:00',
    fechaCierre: '2026-08-12T20:05:00',
    usuarioApertura: 'Camila',
    usuarioCierre: 'Camila',
    baseInicial: 200000,
    efectivoEsperado: 292700,
    efectivoContado: 292700,
    diferencia: 0,
    montoRetirado: 250000,
    baseSiguiente: null,
    observaciones: null,
    notas: [],
    ...ajustes,
  }
}

/**
 * Un movimiento con la forma de MovimientoCajaDto, CON SU MONTO.
 *
 * El monto va aqui a proposito: el backend si lo manda, y una prueba que afirme que
 * no aparece en pantalla no demuestra nada si el fixture nunca lo tuvo. Lo que se
 * comprueba es que api/endpoints.js lo descarta en el limite.
 */
export function movimientoDePrueba(campos) {
  return {
    id: 1,
    tipo: 'RETIRO',
    monto: -50000,
    concepto: 'Consignación',
    fecha: '2026-08-13T08:40:00',
    usuario: 'Camila',
    ventaId: null,
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

/**
 * Un resumen con la forma de MetricasDto.Resumen. El costo y el margen van puestos a
 * mano, no derivados del ingreso: el servidor los calcula y el fixture no puede
 * confundirse con la pantalla.
 */
export function resumenDeMetricas(campos = {}) {
  return {
    ventas: 12,
    unidades: 30,
    ingreso: 600000,
    costo: 360000,
    margen: 240000,
    margenPorcentaje: 40,
    ...campos,
  }
}

/** Un resumen de un periodo sin ventas: todo en cero y el porcentaje en NULL, no en 0. */
export const RESUMEN_SIN_VENTAS = {
  ventas: 0, unidades: 0, ingreso: 0, costo: 0, margen: 0, margenPorcentaje: null,
}

/** El panel con la forma de MetricasDto.Panel, de un dia con ventas. */
export function panelDePrueba(campos = {}) {
  return {
    agrupacion: 'FECHA_DE_VENTA',
    periodo: { tipo: 'DIA', desde: '2026-08-15', hasta: '2026-08-15' },
    periodoAnterior: { tipo: 'DIA', desde: '2026-08-14', hasta: '2026-08-14' },
    actual: resumenDeMetricas(),
    anterior: resumenDeMetricas({ ventas: 10, unidades: 25, ingreso: 500000, costo: 300000,
      margen: 200000, margenPorcentaje: 40 }),
    porMetodoPago: [
      { metodo: 'EFECTIVO', cantidad: 8, total: 400000 },
      { metodo: 'NEQUI', cantidad: 4, total: 200000 },
    ],
    // Las dos listas van en ORDEN DISTINTO a proposito: lo que se comprueba es que la
    // pantalla respeta el orden de cada una y no reordena por su cuenta.
    masVendidosPorUnidades: [
      { varianteId: 1, descripcion: 'Essence|Delineador|Negro', unidades: 9, ingreso: 90000,
        costo: 63000, margen: 27000, margenPorcentaje: 30 },
      { varianteId: 2, descripcion: 'Loréal|Base líquida|Beige', unidades: 3, ingreso: 210000,
        costo: 90000, margen: 120000, margenPorcentaje: 57 },
    ],
    masVendidosPorMargen: [
      { varianteId: 2, descripcion: 'Loréal|Base líquida|Beige', unidades: 3, ingreso: 210000,
        costo: 90000, margen: 120000, margenPorcentaje: 57 },
      { varianteId: 1, descripcion: 'Essence|Delineador|Negro', unidades: 9, ingreso: 90000,
        costo: 63000, margen: 27000, margenPorcentaje: 30 },
    ],
    inventario: {
      valorACosto: 8450000,
      unidades: 640,
      variantesBajoMinimo: [
        { varianteId: 3, descripcion: 'Maybelline|Máscara|Negra', stock: 1, stockMinimo: 4 },
        { varianteId: 4, descripcion: 'Loréal|Labial mate|Nude', stock: -2, stockMinimo: 3 },
      ],
    },
    vencimientos: { vencidos: 2, hasta30: 3, entre31y60: 1, entre61y90: 0 },
    ...campos,
  }
}

/** Un panel de un dia en que no se vendio nada, con la forma exacta que manda el backend. */
export function panelSinVentas() {
  return panelDePrueba({
    actual: RESUMEN_SIN_VENTAS,
    anterior: RESUMEN_SIN_VENTAS,
    porMetodoPago: [],
    masVendidosPorUnidades: [],
    masVendidosPorMargen: [],
    inventario: { valorACosto: 0, unidades: 0, variantesBajoMinimo: [] },
    vencimientos: { vencidos: 0, hasta30: 0, entre31y60: 0, entre61y90: 0 },
  })
}

/** MetricasDto.Vencimiento. */
export function vencimientoDePrueba(campos = {}) {
  return {
    varianteId: 10,
    descripcion: 'Essence|Sombra|Bronce',
    fechaVencimiento: '2026-08-01',
    diasParaVencer: -14,
    stock: 5,
    valorACosto: 25000,
    paoMeses: null,
    ...campos,
  }
}

/** MetricasDto.SinRotacion. `aclaracion` es la que escribe el servidor. */
export function sinRotacionDePrueba(campos = {}) {
  return {
    dias: 90,
    agrupacion: 'FECHA_DE_VENTA',
    aclaracion: 'Sin ventas en 90 días no es lo mismo que nunca vendido: esta lista no distingue '
      + 'el producto que dejó de venderse del que jamás se vendió.',
    filas: [
      { varianteId: 20, descripcion: 'Essence|Rubor|Coral', stock: 6, costoPromedio: 12000,
        valorACosto: 72000 },
      { varianteId: 21, descripcion: 'Loréal|Polvo|Translúcido', stock: 2, costoPromedio: 30000,
        valorACosto: 60000 },
    ],
    ...campos,
  }
}
