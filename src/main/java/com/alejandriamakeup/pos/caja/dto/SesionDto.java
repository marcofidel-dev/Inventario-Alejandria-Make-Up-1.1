package com.alejandriamakeup.pos.caja.dto;

import java.util.List;

/**
 * Una sesión de caja vista desde la API. Tiene dos formas y no una con campos
 * nulos, y eso es el corazón del cierre a ciegas.
 *
 * <p>{@link Abierta} <strong>no tiene</strong> campo para {@code efectivoEsperado}
 * ni ningún total: no es que vayan en null, es que no existen. Un campo que existe es un campo que alguien llena "para el debug" y
 * termina en producción; un campo que no existe no se puede filtrar por descuido.
 *
 * @see com.alejandriamakeup.pos.caja.ServicioSesionCaja
 */
public sealed interface SesionDto {

    /**
     * La sesión mientras está abierta. Todo lo que hace falta para operar y nada
     * con lo que se pueda deducir el efectivo esperado.
     */
    record Abierta(
            Long id,
            String consecutivo,
            String estado,
            String fechaApertura,
            String usuarioApertura,
            int cantidadDeMovimientos,

            /**
             * Quedó abierta de un día anterior: no se puede operar sobre ella hasta
             * cerrarla. El front lo usa para mostrar el aviso; la regla la impone
             * {@code ServicioSesionCaja.sesionOperableHoy()}.
             */
            boolean esDeUnDiaAnterior) implements SesionDto {
    }

    /**
     * La sesión ya cerrada. Aquí sí van los tres valores congelados: el conteo ya
     * se hizo, así que revelarlos no adelanta nada.
     *
     * <p>{@code observaciones} está <strong>en desuso</strong> desde V6: viajaba
     * dentro de {@code CerrarSesionPeticion}, o sea antes de saber si había algo que
     * observar. Se mantiene para las filas que ya lo tengan escrito; lo que se
     * escribe hoy son {@code notas}, que se agregan después y llevan autor y fecha.
     */
    record Cerrada(
            Long id,
            String consecutivo,
            String estado,
            String fechaApertura,
            String fechaCierre,
            String usuarioApertura,
            String usuarioCierre,
            Long efectivoEsperado,
            Long efectivoContado,
            Long diferencia,
            Long montoRetirado,
            String observaciones,

            /**
             * Lo que se anotó sobre esta sesión, en orden. Una sesión con diferencia
             * distinta de cero y sin ninguna nota es una que nadie explicó todavía,
             * y la pantalla la marca así.
             */
            List<NotaSesionCajaDto> notas) implements SesionDto {
    }
}
