package com.alejandriamakeup.pos.configuracion.dto;

import jakarta.validation.constraints.Size;

/**
 * Los datos con los que se encabeza un recibo.
 *
 * <p><strong>Ningún campo es obligatorio, y eso es deliberado.</strong> Con
 * {@code @NotBlank} en el nombre, una tienda recién instalada no podría guardar el
 * teléfono hasta tener decidido el nombre comercial, y —peor— la validación
 * empujaría a inventar un valor para poder seguir. La pantalla advierte que falta el
 * nombre; el recibo se emite igual con el encabezado incompleto.
 *
 * <p>El tope de 60 caracteres no es una restricción de negocio sino del papel: el
 * recibo tiene 42 columnas y una línea de encabezado más larga que eso se envuelve y
 * deja de verse centrada. Es el único sitio donde estos datos se usan.
 */
public record DatosTiendaDto(
        @Size(max = 60) String nombre,
        @Size(max = 60) String nit,
        @Size(max = 60) String direccion,
        @Size(max = 60) String telefono,
        @Size(max = 60) String pieRecibo) {
}
