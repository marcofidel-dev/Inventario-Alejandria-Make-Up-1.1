/**
 * El unico sitio del front que habla HTTP.
 *
 * Todo error del backend llega con la misma forma —{codigo, error, detalles?}—
 * porque asi lo garantiza GlobalExceptionHandler y lo verifica
 * FormaDeLosErroresTest. Aqui se traduce a un objeto con el que las pantallas
 * pueden ramificar sobre `codigo` sin leer el mensaje: el mensaje esta escrito
 * para mostrarse tal cual, y por eso mismo puede cambiar de redaccion sin aviso.
 */

/** Un fallo con el que la pantalla puede decidir que mostrar. */
export class ErrorApi extends Error {
  constructor({ estado, codigo, mensaje, detalles, datos }) {
    super(mensaje)
    this.name = 'ErrorApi'
    this.estado = estado
    this.codigo = codigo
    this.detalles = detalles ?? []
    this.datos = datos ?? {}
  }

  /**
   * Sin conexion no es lo mismo que servidor caido, y en pantalla tampoco:
   * uno dice "revisa el cable", el otro "el programa falló". Distinguirlos es
   * la diferencia entre que alguien llame al tecnico o reinicie el router.
   */
  get esFalloDeRed() {
    return this.codigo === 'SIN_CONEXION'
  }

  get esErrorDelServidor() {
    return this.estado >= 500
  }

  get esSesionVencida() {
    return this.estado === 401
  }
}

/** Los codigos que las pantallas necesitan reconocer. */
export const CODIGOS = {
  noAutenticado: 'NO_AUTENTICADO',
  credencialesInvalidas: 'CREDENCIALES_INVALIDAS',
  demasiadosIntentos: 'DEMASIADOS_INTENTOS',
  sinPermiso: 'SIN_PERMISO',
  configuracionInicialRequerida: 'CONFIGURACION_INICIAL_REQUERIDA',
  configuracionInicialYaHecha: 'CONFIGURACION_INICIAL_YA_HECHA',
  nombreDuplicado: 'NOMBRE_DUPLICADO',
  unicidadViolada: 'UNICIDAD_VIOLADA',
  validacionFallida: 'VALIDACION_FALLIDA',
  cargaInicialYaRegistrada: 'CARGA_INICIAL_YA_REGISTRADA',
  peticionInvalida: 'PETICION_INVALIDA',
  sinConexion: 'SIN_CONEXION',
  noEncontrado: 'NO_ENCONTRADO',
  estadoDeCompraInvalido: 'ESTADO_DE_COMPRA_INVALIDO',
  proveedorInactivo: 'PROVEEDOR_INACTIVO',
  compraSinLineas: 'COMPRA_SIN_LINEAS',
}

/** Quien quiera enterarse de que la sesion se cayo, se suscribe aqui. */
const oyentesDeSesionVencida = new Set()

export function alVencerLaSesion(oyente) {
  oyentesDeSesionVencida.add(oyente)
  return () => oyentesDeSesionVencida.delete(oyente)
}

async function pedir(metodo, ruta, cuerpo) {
  let respuesta
  try {
    respuesta = await fetch(ruta, {
      method: metodo,
      headers: cuerpo === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    })
  } catch {
    // fetch solo rechaza cuando no hubo respuesta: sin red, servidor apagado,
    // puerto cerrado. Un 500 si llega aqui como respuesta, no como excepcion.
    throw new ErrorApi({
      estado: 0,
      codigo: CODIGOS.sinConexion,
      mensaje: 'No hay conexión con el servidor. Puede estar apagado o sin red.',
    })
  }

  if (respuesta.status === 204) return null

  const texto = await respuesta.text()
  const datos = texto ? seguroJson(texto) : null

  if (respuesta.ok) return datos

  const error = new ErrorApi({
    estado: respuesta.status,
    codigo: datos?.codigo ?? 'ERROR_DESCONOCIDO',
    mensaje: datos?.error ?? 'El servidor respondió con un error inesperado.',
    detalles: datos?.detalles,
    datos: datos ?? {},
  })

  if (error.esSesionVencida) {
    oyentesDeSesionVencida.forEach((oyente) => oyente())
  }
  throw error
}

function seguroJson(texto) {
  try {
    return JSON.parse(texto)
  } catch {
    return null
  }
}

export const api = {
  get: (ruta) => pedir('GET', ruta),
  post: (ruta, cuerpo) => pedir('POST', ruta, cuerpo),
  put: (ruta, cuerpo) => pedir('PUT', ruta, cuerpo),
}
