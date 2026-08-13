/**
 * La misma normalizacion que NombreNormalizado.de() en el backend: descomponer
 * en NFD, quitar las marcas diacriticas y pasar a mayusculas.
 *
 * Que sean equivalentes no es un detalle estetico: si el front normalizara de
 * otra forma, el buscador y el validador de duplicados no se pondrian de
 * acuerdo. Buscar "loreal" encuentra "Loréal" en pantalla exactamente porque el
 * backend tambien los considera el mismo nombre.
 *
 * Lo que esto NO hace, y conviene tener claro: solo quita diacriticos. El
 * apostrofo, los espacios internos y la puntuacion se conservan, asi que
 * "L'Oréal" y "Loreal" son nombres distintos para el buscador y tambien para el
 * indice unico. Por eso las marcas se eligen de un desplegable y no se teclean:
 * el indice atrapa las variaciones de tilde y mayuscula, y el desplegable atrapa
 * lo que el indice no puede.
 */
export function normalizar(texto) {
  if (texto === null || texto === undefined) return ''
  return String(texto)
    .trim()
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toUpperCase()
}

/** Si el termino aparece en el texto, ignorando tildes y mayusculas. */
export function contiene(texto, termino) {
  return normalizar(texto).includes(normalizar(termino))
}
