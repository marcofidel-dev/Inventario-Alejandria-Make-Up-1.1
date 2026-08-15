package com.alejandriamakeup.pos.caja.dto;

import com.alejandriamakeup.pos.caja.NotaSesionCaja;

/**
 * Una nota sobre una sesión de caja, vista desde la API.
 *
 * <p>Lleva quién y cuándo porque sin eso no sirve: una explicación anónima de un
 * descuadre no se le puede preguntar a nadie.
 */
public record NotaSesionCajaDto(
        Long id,
        String texto,
        String fecha,
        String usuario) {

    public static NotaSesionCajaDto de(NotaSesionCaja nota) {
        return new NotaSesionCajaDto(
                nota.getId(),
                nota.getTexto(),
                String.valueOf(nota.getFecha()),
                nota.getUsuario().getNombre());
    }
}
