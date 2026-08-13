/**
 * Un boton.
 *
 * `ocupado` lo deshabilita y cambia su texto. No es cosmetica: sin eso, dos
 * clics seguidos en "Guardar" crean dos marcas, o dos cargas iniciales de las
 * que la segunda revienta con 409 dejando al usuario mirando un error que el
 * mismo provoco sin saberlo. El boton se bloquea mientras hay una peticion en
 * vuelo.
 *
 * Sin animacion de giro a proposito. Un indicador que rota necesita una vuelta
 * de alrededor de un segundo para no parecer nervioso, y eso son mil
 * milisegundos: cuatro veces el techo de 240ms que fija el sistema. En vez de
 * pedirle una excepcion a la regla, el boton lo dice con palabras, que ademas se
 * lee sin interpretar.
 */
export function Boton({
  variante = 'normal',
  ocupado = false,
  textoOcupado,
  icono: Icono,
  children,
  disabled,
  ...resto
}) {
  const clases = ['boton']
  if (variante !== 'normal') clases.push(`boton--${variante}`)

  return (
    <button
      type="button"
      className={clases.join(' ')}
      disabled={disabled || ocupado}
      aria-busy={ocupado || undefined}
      {...resto}
    >
      {Icono && !ocupado && <Icono size={16} aria-hidden="true" />}
      {ocupado ? (textoOcupado ?? 'Guardando…') : children}
    </button>
  )
}
