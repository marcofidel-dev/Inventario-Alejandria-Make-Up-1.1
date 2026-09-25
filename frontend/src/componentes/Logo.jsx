import logo from '../assets/logo.svg'

/**
 * El logo de la tienda. Un solo SVG en `assets/`, vectorial: el .jpeg original se
 * veia borroso en cuanto cambiaba la escala de Windows, y la guarda de
 * `sin-rasterizadas` no deja entrar ninguno.
 *
 * `completo` es el logo entero, con nombre y lema: legible desde unos 8rem.
 * `marca` recorta el mismo archivo a la bolsa con la A, para el encabezado, donde el
 * nombre no cabria legible. El recorte es un fragmento `#svgView(viewBox(...))` de la
 * URL: un solo archivo en vez de dos que puedan divergir.
 *
 * El fondo negro va DENTRO del SVG: los blancos del logo desaparecen sobre cualquier
 * superficie clara, asi que el logo siempre viaja con su propio fondo.
 */
const MARCA = '#svgView(viewBox(240,10,780,780))'

export function Logo({ variante = 'completo', className = '' }) {
  const marca = variante === 'marca'
  return (
    <img
      className={`logo logo--${variante} ${className}`.trim()}
      src={marca ? logo + MARCA : logo}
      alt={marca ? '' : 'Alejandria MakeUp'}
      draggable={false}
    />
  )
}
