package com.alejandriamakeup.pos.seguridad;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.alejandriamakeup.pos.usuarios.Rol;

/**
 * Qué puede hacer cada rol.
 *
 * <p>La EMPLEADA se define como <strong>lista explícita de permitidos</strong>, no
 * como "todos menos estos". La diferencia importa: escrito al revés, un
 * {@link Permiso} nuevo quedaría concedido a la EMPLEADA por el simple hecho de
 * existir, y nadie se daría cuenta. Así queda denegado hasta que alguien decida
 * lo contrario a mano.
 *
 * <p>Vive aparte de {@link Rol} para que el enum del dominio — que está atado al
 * CHECK de la columna {@code usuario.rol} — no cargue con la autorización.
 */
public final class PermisosPorRol {

    /** Todo lo que la EMPLEADA sí puede. Lo que no esté aquí, no. */
    private static final Set<Permiso> DE_EMPLEADA = Collections.unmodifiableSet(EnumSet.of(
            Permiso.OPERAR_CAJA,
            Permiso.REGISTRAR_MOVIMIENTO_CAJA,
            Permiso.RESPALDAR));

    /** La DUENA puede todo, incluido cualquier permiso que se agregue mañana. */
    private static final Set<Permiso> DE_DUENA =
            Collections.unmodifiableSet(EnumSet.allOf(Permiso.class));

    private PermisosPorRol() {
    }

    public static boolean puede(Rol rol, Permiso permiso) {
        return de(rol).contains(permiso);
    }

    public static Set<Permiso> de(Rol rol) {
        return switch (rol) {
            case DUENA -> DE_DUENA;
            case EMPLEADA -> DE_EMPLEADA;
        };
    }
}
